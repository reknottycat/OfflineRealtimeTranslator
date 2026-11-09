#include "lmt_engine.hpp"
#include <chrono>
#include <iostream>
static std::string cell(std::string text) {
    for (auto& ch : text) if (ch == '\n' || ch == '\r' || ch == '\t') ch = ' ';
    return text;
}
int main(int argc, char** argv) {
    if (argc != 2) { std::cerr << "Usage: hymt2-smoke model.gguf\n"; return 2; }
    try {
        // A Hunyuan file must not be accepted as the Qwen3/LMT profile.
        bool rejected = false;
        try { lmt::Engine wrong(argv[1], lmt::Profile::Lmt); }
        catch (const std::runtime_error& e) {
            rejected = std::string(e.what()).find("does not match GGUF architecture") != std::string::npos;
        }
        if (!rejected) throw std::runtime_error("Wrong-profile model was not rejected");
        lmt::Engine engine(argv[1], lmt::Profile::HyMt2);
        for (const auto& input : {
            "Здравствуйте. Мы хотим купить 300 тонн кукурузы.",
            "Цена составляет 1000 рублей за тонну.",
            "Мы не согласны с изменением цены.",
            "Содержание влаги не должно превышать 14 процентов.",
            "Поставка должна состояться в течение 30 дней после оплаты.",
            "Просим прислать сертификат качества и результаты испытаний.",
            "Сумма договора составляет 20000 долларов США.",
            "Клапан необходимо закрыть перед проведением испытаний."
        }) {
            std::string streamed;
            int callbacks = 0;
            const auto start = std::chrono::steady_clock::now();
            const auto output = engine.translate(input, [&](const std::string& part) { streamed += part; ++callbacks; });
            const auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
            if (output != streamed || callbacks != 1) throw std::runtime_error("HY validated utterance delivery is not atomic");
            std::cout << input << '\t' << cell(output) << '\t' << cell(engine.last_raw_output()) << '\t' << ms << '\n';
        }
        engine.cancel();
        bool cancelled = false;
        try { engine.translate("Привет"); } catch (const std::runtime_error&) { cancelled = true; }
        if (!cancelled) throw std::runtime_error("Cancelled HY engine still generates");
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
