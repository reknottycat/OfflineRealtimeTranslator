#pragma once
#include <stdexcept>
#include <string>

namespace lmt {
enum class Profile { Lmt = 0, HyMt2 = 1 };
inline Profile profile_from_id(int id) {
    if (id == 0) return Profile::Lmt;
    if (id == 1) return Profile::HyMt2;
    throw std::invalid_argument("Unknown native translation profile");
}
inline const char* expected_architecture(Profile p) {
    return p == Profile::HyMt2 ? "hunyuan-dense" : "qwen3";
}
inline std::string escape_source(std::string text) {
    if (text.empty()) throw std::invalid_argument("Empty translation input");
    if (text.size() > 32768) throw std::invalid_argument("Translation input exceeds 32 KiB");
    for (const std::string& marker : {std::string("<|"), std::string("<｜")}) {
        size_t pos = 0;
        while ((pos = text.find(marker, pos)) != std::string::npos) {
            text.insert(pos + 1, " ");
            pos += marker.size() + 1;
        }
    }
    return text;
}
// NiuTrans's prompt remains unchanged. HY uses Tencent's single-user chat
// template (BOS + user + content + assistant), with no system/default persona.
inline std::string build_prompt(std::string source, Profile profile = Profile::Lmt) {
    source = escape_source(source);
    if (profile == Profile::HyMt2) {
        return "<｜hy_begin▁of▁sentence｜><｜hy_User｜>"
            "将以下文本翻译为简体中文，注意只需要输出翻译后的结果，不要额外解释：\n"
            + source + "<｜hy_Assistant｜>";
    }
    return "<|im_start|>user\nTranslate the following text from Russian into Chinese:\nRussian: "
        + source + "\nChinese:<|im_end|>\n<|im_start|>assistant\n";
}
inline std::string trim_output(std::string s) {
    const auto first = s.find_first_not_of(" \r\n\t");
    if (first == std::string::npos) return {};
    return s.substr(first, s.find_last_not_of(" \r\n\t") - first + 1);
}
// HY is delivered at utterance boundaries. Hold the whole response until EOS
// so a reasoning block / partial control token can never leak into subtitles.
inline std::string clean_hymt_output(std::string s) {
    s = trim_output(s);
    if (s.rfind("<think>", 0) == 0) {
        const auto end = s.find("</think>", 7);
        if (end == std::string::npos) throw std::runtime_error("HY-MT2 returned an incomplete reasoning block");
        s = trim_output(s.substr(end + 8));
    }
    if (s.rfind("<answer>", 0) == 0) {
        const auto end = s.find("</answer>", 8);
        if (end == std::string::npos || !trim_output(s.substr(end + 9)).empty())
            throw std::runtime_error("HY-MT2 returned an invalid answer block");
        s = trim_output(s.substr(8, end - 8));
    }
    if (s.empty()) throw std::runtime_error("HY-MT2 returned no translation");
    for (const auto* token : {"<think>", "</think>", "<answer>", "</answer>", "<｜hy_", "<|im_"})
        if (s.find(token) != std::string::npos) throw std::runtime_error("HY-MT2 returned unexpected control tokens");
    return s;
}
inline constexpr const char* hymt_currency_warning =
    "[HY-MT2 币种校验未通过：原文含卢布，译文未保留币种，请核对原文。]";
} // namespace lmt
