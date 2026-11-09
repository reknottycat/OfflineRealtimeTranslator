#pragma once
#include <string>
#include <vector>

namespace lmt {
inline constexpr const char* currency_warning =
    "[LMT 币种校验未通过：原文含卢布，译文未保留币种，请核对原文。]";

// Minimal, explicit case folding for ASCII and Russian Cyrillic. All other UTF-8
// bytes are preserved. This is a narrow known-error guard, not a quality oracle.
inline std::string lower_russian(const std::string& input) {
    std::string result;
    for (size_t i = 0; i < input.size(); ++i) {
        auto c = static_cast<unsigned char>(input[i]);
        if (c >= 'A' && c <= 'Z') { result += static_cast<char>(c + 32); continue; }
        if (c == 0xd0 && i + 1 < input.size()) {
            auto d = static_cast<unsigned char>(input[i + 1]);
            if (d >= 0x90 && d <= 0xaf) {
                unsigned cp = 0x0400 + (d & 0x3f) + 32;
                result += static_cast<char>(0xc0 | (cp >> 6));
                result += static_cast<char>(0x80 | (cp & 0x3f));
                ++i; continue;
            }
            if (d == 0x81) { result += "ё"; ++i; continue; }
        }
        result += input[i];
    }
    return result;
}
inline bool adjacent_letter(unsigned char c) {
    // Non-ASCII bytes count as letters conservatively, so words like 'зарубка'
    // cannot accidentally be classified as the currency abbreviation 'руб'.
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c >= 0x80;
}
inline bool contains_token(const std::string& value, const std::string& token) {
    size_t pos = 0;
    while ((pos = value.find(token, pos)) != std::string::npos) {
        auto end = pos + token.size();
        if ((pos == 0 || !adjacent_letter(static_cast<unsigned char>(value[pos - 1]))) &&
            (end == value.size() || !adjacent_letter(static_cast<unsigned char>(value[end])))) return true;
        ++pos;
    }
    return false;
}
inline bool mentions_rubles(const std::string& source) {
    if (source.find("₽") != std::string::npos) return true;
    auto normalized = lower_russian(source);
    for (const auto* term : {"рубль", "рубля", "рублей", "рубли", "рублю", "рублём", "рублем",
                             "рублям", "рублях", "рублями", "руб", "rub"}) {
        if (contains_token(normalized, term)) return true;
    }
    return false;
}
inline std::string validate_currency(const std::string& source, const std::string& output) {
    if (!mentions_rubles(source)) return output;
    const auto folded = lower_russian(output);
    const bool has_ruble = output.find("卢布") != std::string::npos ||
        output.find("盧布") != std::string::npos || output.find("₽") != std::string::npos ||
        contains_token(folded, "rub");
    return has_ruble ? output : currency_warning;
}
} // namespace lmt
