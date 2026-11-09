#include "lmt_engine.hpp"
#include <iostream>
int main(int argc, char** argv) {
    if (argc != 2) { std::cerr << "Usage: lmt-smoke model.gguf\n"; return 2; }
    try {
        lmt::Engine engine(argv[1]);
        for (const auto& input : {"Здравствуйте. Мы хотим купить 300 тонн кукурузы.", "Цена составляет 1000 рублей за тонну."}) {
            auto output = engine.translate(input);
            std::replace(output.begin(), output.end(), '\n', ' ');
            std::cout << input << '\t' << output << '\n';
        }
        engine.cancel();
        bool cancelled = false;
        try { engine.translate("Привет"); } catch (const std::runtime_error&) { cancelled = true; }
        if (!cancelled) throw std::runtime_error("Cancellation did not stop inference");
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
