// Copyright 2025 The ODML Authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// ODML pipeline to execute or benchmark LLM graph on device.
//
// The pipeline does the following
// 1) Read the corresponding parameters, weight and model file paths.
// 2) Construct a graph model with the setting.
// 3) Execute model inference and generate the output.

#include <fstream>
#include <iostream>
#include <memory>
#include <sstream>
#include <string>
#include <utility>
#include <variant>

#include "absl/base/log_severity.h"  // from @com_google_absl
#include "absl/flags/flag.h"  // from @com_google_absl
#include "absl/flags/parse.h"  // from @com_google_absl
#include "absl/functional/any_invocable.h"  // from @com_google_absl
#include "absl/log/absl_check.h"  // from @com_google_absl
#include "absl/log/absl_log.h"  // from @com_google_absl
#include "absl/log/globals.h"  // from @com_google_absl
#include "absl/status/status.h"  // from @com_google_absl
#include "absl/status/statusor.h"  // from @com_google_absl
#include "absl/strings/string_view.h"  // from @com_google_absl
#include "absl/time/time.h"  // from @com_google_absl
#include "nlohmann/json.hpp"  // from @nlohmann_json
#include "litert/cc/internal/scoped_file.h"  // from @litert
#include "runtime/conversation/conversation.h"
#include "runtime/conversation/io_types.h"
#include "runtime/engine/engine.h"
#include "runtime/engine/engine_factory.h"
#include "runtime/engine/engine_settings.h"
#include "runtime/engine/io_types.h"
#include "runtime/executor/executor_settings_base.h"
#include "runtime/util/status_macros.h"

ABSL_FLAG(std::string, backend, "gpu",
          "Executor backend to use for LLM execution (cpu, gpu, npu, etc.)");
ABSL_FLAG(std::string, model_path, "", "Model path to use for LLM execution.");
ABSL_FLAG(std::string, input_prompt, "",
          "Input prompt to use for testing LLM execution.");
ABSL_FLAG(std::string, input_prompt_file, "", "File path to the input prompt.");
ABSL_FLAG(std::string, litert_dispatch_lib_dir, "",
          "Directory containing LiteRT NPU dispatch library (e.g. "
          "libLiteRtDispatch_GoogleTensor.so). Required for --backend=npu.");
ABSL_FLAG(bool, poll_e_worker, false,
          "Keep the model resident and read Poll-E screen snapshots from "
          "stdin, emitting one bounded suggestion per request.");
ABSL_FLAG(std::string, poll_e_profile_file, "",
          "Optional Poll-E profile/policy file to prefill once at startup.");
ABSL_FLAG(int32_t, poll_e_max_output_tokens, 48,
          "Maximum tokens to decode for each Poll-E worker response.");
ABSL_FLAG(bool, poll_e_use_primed_clone, true,
          "For Poll-E worker mode, keep one primed conversation alive and "
          "clone it for each request. Falls back to fresh conversations if "
          "clone is not supported by the selected backend.");

namespace litert::lm {
// Defined in engine_advanced_impl.cc — explicit call avoids static-init GC.
void RegisterAdvancedEngine();
}  // namespace litert::lm

namespace {

using ::litert::lm::Backend;
using ::litert::lm::Conversation;
using ::litert::lm::ConversationConfig;
using ::litert::lm::EngineSettings;
using ::litert::lm::InputData;
using ::litert::lm::JsonPreface;
using ::litert::lm::Message;
using ::litert::lm::ModelAssets;
using ::litert::lm::OptionalArgs;
using ::nlohmann::json;

absl::AnyInvocable<void(absl::StatusOr<Message>)> CreateMessageCallback() {
  return [](absl::StatusOr<Message> message) {
    if (!message.ok()) {
      std::cout << "Error: " << message.status() << std::endl;
      return;
    }
    if (message->is_null()) {
      std::cout << std::endl << std::flush;
      return;
    }
    for (const auto& content : (*message)["content"]) {
      std::cout << content["text"].get<std::string>();
    }
    std::cout << std::flush;
  };
}

absl::StatusOr<std::string> ReadTextFile(const std::string& path) {
  std::ifstream file(path);
  if (!file.is_open()) {
    return absl::NotFoundError("Could not open file: " + path);
  }
  std::stringstream buffer;
  buffer << file.rdbuf();
  return buffer.str();
}

Message MakeTextMessage(const std::string& role, const std::string& text) {
  json content_list = json::array();
  content_list.push_back({{"type", "text"}, {"text", text}});
  return json::object({{"role", role}, {"content", content_list}});
}

std::string ExtractMessageText(const Message& message) {
  if (!message.contains("content")) return "";
  const auto& content = message["content"];
  if (content.is_string()) return content.get<std::string>();
  if (!content.is_array()) return "";

  std::string text;
  for (const auto& item : content) {
    if (item.is_string()) {
      text += item.get<std::string>();
    } else if (item.contains("text") && item["text"].is_string()) {
      text += item["text"].get<std::string>();
    }
  }
  return text;
}

// Gets the input prompt from the command line flag or file.
absl::StatusOr<std::string> GetInputPrompt() {
  const std::string input_prompt = absl::GetFlag(FLAGS_input_prompt);
  const std::string input_prompt_file = absl::GetFlag(FLAGS_input_prompt_file);
  if (!input_prompt.empty() && !input_prompt_file.empty()) {
    return absl::InvalidArgumentError(
        "Only one of --input_prompt and --input_prompt_file can be specified.");
  }
  if (!input_prompt.empty()) return input_prompt;
  if (!input_prompt_file.empty()) return ReadTextFile(input_prompt_file);
  // If no input prompt is provided, use the default prompt.
  return "What is the tallest building in the world?";
}

std::string BuildPollEPrompt(const std::string& encoded) {
  // Decode literal \n sequences sent by the Android APK over the single-line
  // stdin protocol. The APK encodes actual newlines as backslash-n so the
  // prompt arrives as one line; this restores the original formatting.
  std::string prompt;
  prompt.reserve(encoded.size());
  for (size_t i = 0; i < encoded.size(); i++) {
    if (i + 1 < encoded.size() && encoded[i] == '\\' && encoded[i + 1] == 'n') {
      prompt += '\n';
      i++;
    } else {
      prompt += encoded[i];
    }
  }
  return prompt;
}

absl::Status RunPollEWorker(litert::lm::Engine& engine,
                            const ConversationConfig& conversation_config) {
  const int max_output_tokens = absl::GetFlag(FLAGS_poll_e_max_output_tokens);

  ASSIGN_OR_RETURN(std::unique_ptr<Conversation> base_conversation,
                   Conversation::Create(engine, conversation_config));
  std::cout << "POLL_E_READY" << std::endl;

  std::string snapshot;
  while (std::getline(std::cin, snapshot)) {
    if (snapshot == "__quit__") break;
    if (snapshot.empty()) {
      std::cout << "POLL_E_BEGIN\n\nPOLL_E_END" << std::endl;
      continue;
    }

    OptionalArgs optional_args;
    optional_args.max_output_tokens = max_output_tokens;
    optional_args.enable_thinking = false;

    absl::Time start = absl::Now();
    ASSIGN_OR_RETURN(std::unique_ptr<Conversation> request_conversation,
                     base_conversation->Clone());

    ASSIGN_OR_RETURN(
        Message response,
        request_conversation->SendMessage(
            MakeTextMessage("user", BuildPollEPrompt(snapshot)),
            std::move(optional_args)));
    std::cerr << "Poll-E request_ms="
              << absl::ToInt64Milliseconds(absl::Now() - start) << std::endl;
    std::cout << "POLL_E_BEGIN\n"
              << ExtractMessageText(response)
              << "\nPOLL_E_END" << std::endl;
  }

  return absl::OkStatus();
}

void ParseManualFlags(int argc, char** argv) {
  // absl::ParseCommandLine requires flags registered in the global registry,
  // but FlagRegistrar initialization is elided by -O3 (constexpr + trivial
  // FlagRegistrarEmpty). Parse argv manually and set flags directly instead.
  for (int i = 1; i < argc; ++i) {
    std::string_view arg(argv[i]);
    if (arg.size() < 3 || arg[0] != '-' || arg[1] != '-') continue;
    arg.remove_prefix(2);
    auto eq = arg.find('=');
    std::string key;
    std::string val;
    if (eq == std::string_view::npos) {
      key = std::string(arg);
      if (key == "poll_e_worker") {
        val = "true";
      } else if (i + 1 < argc) {
        val = argv[++i];
      } else {
        continue;
      }
    } else {
      key = std::string(arg.substr(0, eq));
      val = std::string(arg.substr(eq + 1));
    }

    if (key == "backend") absl::SetFlag(&FLAGS_backend, val);
    else if (key == "model_path") absl::SetFlag(&FLAGS_model_path, val);
    else if (key == "input_prompt") absl::SetFlag(&FLAGS_input_prompt, val);
    else if (key == "input_prompt_file") absl::SetFlag(&FLAGS_input_prompt_file, val);
    else if (key == "litert_dispatch_lib_dir") absl::SetFlag(&FLAGS_litert_dispatch_lib_dir, val);
    else if (key == "poll_e_worker") absl::SetFlag(&FLAGS_poll_e_worker, val != "false" && val != "0");
    else if (key == "poll_e_profile_file") absl::SetFlag(&FLAGS_poll_e_profile_file, val);
    else if (key == "poll_e_max_output_tokens") absl::SetFlag(&FLAGS_poll_e_max_output_tokens, std::stoi(val));
    else if (key == "poll_e_use_primed_clone") absl::SetFlag(&FLAGS_poll_e_use_primed_clone, val != "false" && val != "0");
  }
}

absl::Status MainHelper(int argc, char** argv) {
  litert::lm::RegisterAdvancedEngine();
  ParseManualFlags(argc, argv);
  // Overrides the default for FLAGS_minloglevel to error.
  absl::SetMinLogLevel(absl::LogSeverityAtLeast::kError);
  absl::SetStderrThreshold(absl::LogSeverityAtLeast::kFatal);

  const std::string model_path = absl::GetFlag(FLAGS_model_path);
  if (model_path.empty()) {
    return absl::InvalidArgumentError("Model path is empty.");
  }
  ASSIGN_OR_RETURN(ModelAssets model_assets,  // NOLINT
                   ModelAssets::Create(model_path));
  auto backend_str = absl::GetFlag(FLAGS_backend);
  ASSIGN_OR_RETURN(Backend backend,
                   litert::lm::GetBackendFromString(backend_str));
  ASSIGN_OR_RETURN(
      EngineSettings engine_settings,
      EngineSettings::CreateDefault(std::move(model_assets), backend));
  const std::string dispatch_lib_dir =
      absl::GetFlag(FLAGS_litert_dispatch_lib_dir);
  if (!dispatch_lib_dir.empty()) {
    engine_settings.GetMutableMainExecutorSettings().SetLitertDispatchLibDir(
        dispatch_lib_dir);
  }
  // Enable benchmark by default.
  engine_settings.GetMutableBenchmarkParams() =
      litert::lm::proto::BenchmarkParams();

  // Create the engine.
  ASSIGN_OR_RETURN(auto engine, litert::lm::EngineFactory::CreateDefault(
                                    std::move(engine_settings)));

  // Create the conversation.
  std::unique_ptr<Conversation> conversation;
  auto session_config = litert::lm::SessionConfig::CreateDefault();
  auto conversation_config_builder = ConversationConfig::Builder();
  conversation_config_builder.SetSessionConfig(session_config)
      .SetEnableThinking(false);
  const std::string profile_file = absl::GetFlag(FLAGS_poll_e_profile_file);
  if (!profile_file.empty()) {
    ASSIGN_OR_RETURN(const std::string profile_text, ReadTextFile(profile_file));
    JsonPreface preface;
    preface.messages = json::array({MakeTextMessage("system", profile_text)});
    preface.tools = json::array();
    preface.extra_context = json::object();
    conversation_config_builder.SetPreface(preface)
        .SetPrefillPrefaceOnInit(true);
  }
  ASSIGN_OR_RETURN(auto conversation_config,
                   conversation_config_builder.Build(*engine));

  if (absl::GetFlag(FLAGS_poll_e_worker)) {
    return RunPollEWorker(*engine, conversation_config);
  }

  ASSIGN_OR_RETURN(conversation,
                   Conversation::Create(*engine, conversation_config));

  // Prepare the message to send.
  ASSIGN_OR_RETURN(const std::string input_prompt, GetInputPrompt());
  std::cout << "input_prompt: " << input_prompt << std::endl;

  // Send the message and wait for the response, asynchronously log the
  // response.
  RETURN_IF_ERROR(conversation->SendMessageAsync(
      MakeTextMessage("user", input_prompt), CreateMessageCallback()));
  RETURN_IF_ERROR(engine->WaitUntilDone(absl::Minutes(10)));

  // Print the benchmark info.
  auto benchmark_info = conversation->GetBenchmarkInfo();
  std::cout << std::endl << *benchmark_info << std::endl;
  return absl::OkStatus();
}

}  // namespace

int main(int argc, char** argv) {
  ABSL_CHECK_OK(MainHelper(argc, argv));
  return 0;
}
