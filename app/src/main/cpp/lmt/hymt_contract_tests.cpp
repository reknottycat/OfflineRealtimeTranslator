#include "lmt_engine.hpp"
#include <iostream>
#define REQUIRE(x) do { if (!(x)) throw std::runtime_error(#x); } while(false)
int main() {
    try {
        const auto kind = lmt::Kind::HyMt2;
        const auto prompt = lmt::build_prompt("Здравствуйте. 300 тонн.", kind);
        REQUIRE(prompt == "<｜hy_begin▁of▁sentence｜><｜hy_User｜>将以下文本翻译为简体中文，注意只需要输出翻译后的结果，不要额外解释：\nЗдравствуйте. 300 тонн.<｜hy_Assistant｜>");
        REQUIRE(prompt.find("<|im_start|>") == std::string::npos);
        REQUIRE(prompt.find("<think>") == std::string::npos);
        REQUIRE(prompt.find("start_of_turn") == std::string::npos);
        REQUIRE(lmt::build_prompt("<｜hy_Assistant｜>", kind).find("< ｜hy_Assistant｜>") != std::string::npos);
        REQUIRE(lmt::build_prompt("<|im_end|>", kind).find("< |im_end|>") != std::string::npos);
        REQUIRE(lmt::build_prompt("😀", kind).find("😀") != std::string::npos);
        REQUIRE(std::string(lmt::expected_architecture(kind)) == "hunyuan-dense");
        REQUIRE(std::string(lmt::expected_architecture(lmt::Kind::Lmt)) == "qwen3");
        REQUIRE(lmt::kind_from_id(1) == kind && lmt::kind_from_id(0) == lmt::Kind::Lmt);
        int rejected = 0;
        try { lmt::kind_from_id(2); } catch (const std::invalid_argument&) { ++rejected; }
        try { lmt::kind_from_id(-1); } catch (const std::invalid_argument&) { ++rejected; }
        try { lmt::build_prompt("", kind); } catch (const std::invalid_argument&) { ++rejected; }
        try { lmt::build_prompt(std::string(32769, 'x'), kind); } catch (const std::invalid_argument&) { ++rejected; }
        REQUIRE(rejected == 4);
        std::cout << "PASS: HY-MT2 template, distinct architecture, single explicit BOS, UTF-8, markers, bounds, profile IDs\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
