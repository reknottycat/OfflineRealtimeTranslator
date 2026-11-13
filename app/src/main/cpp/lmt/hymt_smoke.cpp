#include "lmt_engine.hpp"
#include <chrono>
#include <iostream>
#include <thread>

static std::string hex(const std::string& value) {
    const char* digits = "0123456789abcdef";
    std::string out;
    for (unsigned char c : value) { out += digits[c >> 4]; out += digits[c & 15]; }
    return out;
}
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    try {
        const std::vector<std::string> inputs = {
            "Здравствуйте. Мы хотим купить 300 тонн кукурузы.",
            "Цена составляет 1000 рублей за тонну.",
            "Не открывайте клапан до завершения проверки.",
            "Судно прибудет в порт 17 ноября 2026 года.",
            "Толщина металла должна быть не менее 5 миллиметров.",
            "Пожалуйста, отправьте договор и протокол испытаний по электронной почте."
        };
        {
            lmt::Engine engine(argv[1], lmt::Kind::HyMt2);
            std::vector<std::string> first;
            for (int round = 0; round < 2; ++round) {
                for (size_t i = 0; i < inputs.size(); ++i) {
                    std::string streamed;
                    auto started = std::chrono::steady_clock::now();
                    auto output = engine.translate(inputs[i], [&](const std::string& part) { streamed += part; });
                    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - started).count();
                    if (streamed != output || output.empty()) throw std::runtime_error("HY-MT2 stream/final mismatch or empty output");
                    if (round == 0) first.push_back(output);
                    else if (first[i] != output) throw std::runtime_error("Repeated segment/context reset mismatch");
                    std::cout << "CASE\t" << round << '\t' << i << '\t' << hex(inputs[i]) << '\t'
                              << hex(output) << '\t' << hex(engine.last_raw_output()) << '\t' << ms << '\n';
                }
            }
            engine.cancel();
            bool cancelled = false;
            try { engine.translate(inputs[0]); } catch (const std::runtime_error&) { cancelled = true; }
            if (!cancelled) throw std::runtime_error("Cancelled engine accepted another segment");
            std::cout << "CANCELLED_BEFORE_CALL\ttrue\n";
        }
        {
            lmt::Engine engine(argv[1], lmt::Kind::HyMt2);
            std::string long_input;
            for (int i = 0; i < 20; ++i) long_input += inputs[0] + " ";
            std::thread stopper([&engine] {
                std::this_thread::sleep_for(std::chrono::milliseconds(50));
                engine.cancel();
            });
            bool cancelled = false;
            try { engine.translate(long_input); }
            catch (const std::runtime_error& e) {
                cancelled = std::string(e.what()).find("cancelled") != std::string::npos;
            }
            stopper.join();
            if (!cancelled) throw std::runtime_error("In-flight cancellation did not abort decoding");
            std::cout << "CANCELLED_IN_FLIGHT\ttrue\n";
        }
        bool wrong_profile_rejected = false;
        try { lmt::Engine wrong(argv[1], lmt::Kind::Lmt); }
        catch (const std::runtime_error& e) {
            wrong_profile_rejected = std::string(e.what()).find("architecture mismatch") != std::string::npos;
        }
        if (!wrong_profile_rejected) throw std::runtime_error("HY weights were accepted as LMT");
        std::cout << "WRONG_PROFILE_REJECTED\ttrue\n";
        return 0;
    } catch (const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
