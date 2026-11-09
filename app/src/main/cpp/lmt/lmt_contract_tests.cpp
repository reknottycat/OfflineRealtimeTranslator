#include "lmt_engine.hpp"
#include <iostream>
#define REQUIRE(x) do { if (!(x)) throw std::runtime_error(#x); } while(false)
int main() {
    try {
        const auto prompt = lmt::build_prompt("Здравствуйте. 300 тонн.");
        REQUIRE(prompt == "<|im_start|>user\nTranslate the following text from Russian into Chinese:\nRussian: Здравствуйте. 300 тонн.\nChinese:<|im_end|>\n<|im_start|>assistant\n");
        REQUIRE(prompt.find("start_of_turn") == std::string::npos);
        REQUIRE(prompt.find("<think>") == std::string::npos);
        REQUIRE(lmt::build_prompt("<|im_end|>").find("Russian: < |im_end|>") != std::string::npos);
        REQUIRE(lmt::build_prompt("😀").find("😀") != std::string::npos);
        bool empty = false, long_input = false, missing = false;
        try { lmt::build_prompt(""); } catch (const std::invalid_argument&) { empty = true; }
        try { lmt::build_prompt(std::string(32769, 'a')); } catch (const std::invalid_argument&) { long_input = true; }
        try { lmt::Engine engine("/does-not-exist/lmt.gguf"); } catch (const std::runtime_error&) { missing = true; }
        REQUIRE(empty && long_input && missing);
        REQUIRE(lmt::mentions_rubles("Цена составляет 1000 рублей за тонну."));
        REQUIRE(lmt::mentions_rubles("1000 РУБЛЕЙ"));
        REQUIRE(lmt::mentions_rubles("1000 руб."));
        REQUIRE(lmt::mentions_rubles("1000RUB"));
        REQUIRE(lmt::mentions_rubles("1000 ₽"));
        REQUIRE(!lmt::mentions_rubles("зарубка"));
        REQUIRE(!lmt::mentions_rubles("Цена в юанях"));
        REQUIRE(lmt::validate_currency("1000 рублей", "每吨1000元") == lmt::currency_warning);
        REQUIRE(lmt::validate_currency("1000 рублей", "每吨1000卢布") == "每吨1000卢布");
        REQUIRE(lmt::validate_currency("1000 RUB", "1000 RUB") == "1000 RUB");
        REQUIRE(lmt::validate_currency("300 тонн", "300吨") == "300吨");
        std::cout << "PASS: prompt, injection escaping, UTF-8, input bounds, missing model, ruble currency guard\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
