#pragma once
#include <stdexcept>
#include <string>

namespace lmt {
enum class Kind { Lmt = 0, HyMt2 = 1 };
inline Kind kind_from_id(int value) {
    if (value == 0) return Kind::Lmt;
    if (value == 1) return Kind::HyMt2;
    throw std::invalid_argument("Unknown GGUF translation profile");
}
inline const char* profile_name(Kind kind) {
    switch (kind) {
        case Kind::Lmt: return "LMT";
        case Kind::HyMt2: return "HY-MT2";
    }
    throw std::invalid_argument("Invalid GGUF translation profile");
}
inline const char* expected_architecture(Kind kind) {
    return kind == Kind::HyMt2 ? "hunyuan-dense" : "qwen3";
}
inline void escape_marker(std::string& value, const std::string& marker, const std::string& replacement) {
    size_t pos = 0;
    while ((pos = value.find(marker, pos)) != std::string::npos) {
        value.replace(pos, marker.size(), replacement);
        pos += replacement.size();
    }
}
/** Single-segment templates; never silently reuse LMT's ChatML for Hunyuan. */
inline std::string build_prompt(std::string source, Kind kind = Kind::Lmt) {
    profile_name(kind); // Reject invalid enum values before building any prompt.
    if (source.empty()) throw std::invalid_argument("Empty translation input");
    if (source.size() > 32768) throw std::invalid_argument("Translation input exceeds 32 KiB");
    escape_marker(source, "<|", "< |");
    if (kind == Kind::Lmt) {
        return "<|im_start|>user\nTranslate the following text from Russian into Chinese:\nRussian: "
            + source + "\nChinese:<|im_end|>\n<|im_start|>assistant\n";
    }
    escape_marker(source, "<｜", "< ｜");
    // Tencent's chat_template.jinja: BOS + User + content + Assistant.
    // No default system prompt and no artificial thinking prefix.
    return "<｜hy_begin▁of▁sentence｜><｜hy_User｜>"
        "将以下文本翻译为简体中文，注意只需要输出翻译后的结果，不要额外解释：\n"
        + source + "<｜hy_Assistant｜>";
}
} // namespace lmt
