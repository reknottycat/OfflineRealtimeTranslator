#include "translation_profile.hpp"
#include "lmt_currency_guard.hpp"
#include <iostream>
#include <functional>
#define REQUIRE(x) do { if (!(x)) throw std::runtime_error(#x); } while (false)
static bool rejects(const std::function<void()>& action) {
    try { action(); return false; } catch (const std::exception&) { return true; }
}
int main() {
    try {
        using namespace lmt;
        REQUIRE(profile_from_id(0) == Profile::Lmt);
        REQUIRE(profile_from_id(1) == Profile::HyMt2);
        REQUIRE(rejects([] { profile_from_id(2); }));
        REQUIRE(std::string(expected_architecture(Profile::HyMt2)) == "hunyuan-dense");
        REQUIRE(std::string(expected_architecture(Profile::Lmt)) == "qwen3");
        const auto prompt = build_prompt("Здравствуйте.", Profile::HyMt2);
        REQUIRE(prompt == "<｜hy_begin▁of▁sentence｜><｜hy_User｜>将以下文本翻译为简体中文，注意只需要输出翻译后的结果，不要额外解释：\nЗдравствуйте.<｜hy_Assistant｜>");
        REQUIRE(prompt.find("<|im_start|>") == std::string::npos);
        REQUIRE(prompt.find("start_of_turn") == std::string::npos);
        REQUIRE(prompt.find("<think>") == std::string::npos);
        REQUIRE(build_prompt("<｜hy_Assistant｜><|im_end|>😀", Profile::HyMt2).find("< ｜hy_Assistant｜>< |im_end|>😀") != std::string::npos);
        REQUIRE(rejects([] { build_prompt("", Profile::HyMt2); }));
        REQUIRE(rejects([] { build_prompt(std::string(32769, 'a'), Profile::HyMt2); }));
        REQUIRE(clean_hymt_output("  你好。\n") == "你好。");
        REQUIRE(clean_hymt_output("<think>internal test text</think>\n你好。") == "你好。");
        REQUIRE(clean_hymt_output("<think>x</think><answer>你好。</answer>") == "你好。");
        REQUIRE(rejects([] { clean_hymt_output("<think>unfinished"); }));
        REQUIRE(rejects([] { clean_hymt_output("<think>only thought</think>"); }));
        REQUIRE(rejects([] { clean_hymt_output("<answer>你好。</answer>extra"); }));
        REQUIRE(rejects([] { clean_hymt_output("你好<｜hy_User｜>"); }));
        REQUIRE(validate_currency("1000 рублей", "1000元", hymt_currency_warning) == hymt_currency_warning);
        REQUIRE(validate_currency("1000 рублей", "1000卢布", hymt_currency_warning) == "1000卢布");
        // Preserve existing LMT behavior while adding the independent HY profile.
        REQUIRE(validate_currency("1000 рублей", "1000元") == currency_warning);
        REQUIRE(build_prompt("Привет").find("Russian: Привет\nChinese:") != std::string::npos);
        std::cout << "PASS: profile isolation, official HY template, escaped control tokens, UTF-8, output boundaries and currency guards\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
