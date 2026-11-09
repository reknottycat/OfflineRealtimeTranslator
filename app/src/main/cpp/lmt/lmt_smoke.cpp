#include "lmt_engine.hpp"
#include <iostream>
int main(int argc, char** argv) {
    if (argc != 2) { std::cerr << "Usage: lmt-smoke model.gguf\n"; return 2; }
    try {
        lmt::Engine engine(argv[1]);
        for (const auto& input : {"Здравствуйте. Мы хотим купить 300 тонн кукурузы.", "Цена составляет 1000 рублей за тонну."}) {
            std::string streamed;
            auto output = engine.translate(input, [&](const std::string& part) { streamed += part; });
            if (output != streamed) throw std::runtime_error("Stream differs from final validated output");
            auto raw = engine.last_raw_output();
            std::replace(output.begin(), output.end(), '\n', ' ');
            std::replace(raw.begin(), raw.end(), '\n', ' ');
            std::replace(output.begin(), output.end(), '\t', ' ');
            std::replace(raw.begin(), raw.end(), '\t', ' ');
            std::cout << input << '\t' << output << '\t' << raw << '\n';
        }
        engine.cancel();
        bool cancelled = false;
        try { engine.translate("Привет"); } catch (const std::runtime_error&) { cancelled = true; }
        if (!cancelled) throw std::runtime_error("Cancellation did not stop inference");
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
