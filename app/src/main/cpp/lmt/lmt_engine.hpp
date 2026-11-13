#pragma once
#include "llama.h"
#include "lmt_currency_guard.hpp"
#include "translation_profile.hpp"
#include <algorithm>
#include <atomic>
#include <functional>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace lmt {
struct ModelDeleter { void operator()(llama_model* p) const { if(p) llama_model_free(p); } };
struct ContextDeleter { void operator()(llama_context* p) const { if(p) llama_free(p); } };
struct SamplerDeleter { void operator()(llama_sampler* p) const { if(p) llama_sampler_free(p); } };
class Engine {
    Kind kind_;
    std::atomic<bool> cancelled_{false};
    std::unique_ptr<llama_model, ModelDeleter> model_;
    std::unique_ptr<llama_context, ContextDeleter> ctx_;
    std::unique_ptr<llama_sampler, SamplerDeleter> sampler_;
    std::string last_raw_output_;
    static bool aborted(void* opaque) {
        return static_cast<Engine*>(opaque)->cancelled_.load(std::memory_order_relaxed);
    }
    void check_cancelled() const {
        if (cancelled_.load(std::memory_order_relaxed)) throw std::runtime_error("Translation inference cancelled");
    }
    std::string checked_currency(const std::string& source, const std::string& output) const {
        auto checked = validate_currency(source, output);
        if (kind_ == Kind::HyMt2 && checked == currency_warning) {
            return "[HY-MT2 币种校验未通过：原文含卢布，译文未保留币种，请核对原文。]";
        }
        return checked;
    }
public:
    explicit Engine(const std::string& path, Kind kind = Kind::Lmt) : kind_(kind) {
        profile_name(kind_);
        static std::once_flag initialized;
        std::call_once(initialized, [] { llama_backend_init(); });
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.use_mmap = true;
        model_.reset(llama_model_load_from_file(path.c_str(), mp));
        if (!model_) throw std::runtime_error("Cannot load GGUF translation model");
        char arch[64] = {};
        llama_model_meta_val_str(model_.get(), "general.architecture", arch, sizeof(arch));
        if (std::string(arch) != expected_architecture(kind_)) {
            throw std::runtime_error(std::string(profile_name(kind_)) + " model architecture mismatch: " + arch);
        }
        auto cp = llama_context_default_params();
        cp.n_ctx = 2048;
        cp.n_batch = 256;
        cp.n_ubatch = 128;
        cp.n_threads = 4;
        cp.n_threads_batch = 4;
        cp.no_perf = true;
        cp.offload_kqv = false;
        cp.abort_callback = aborted;
        cp.abort_callback_data = this;
        ctx_.reset(llama_init_from_model(model_.get(), cp));
        if (!ctx_) throw std::runtime_error("Cannot allocate translation context");
        // Deterministic mobile baseline; deliberately not advertised as upstream benchmark sampling.
        sampler_.reset(llama_sampler_init_greedy());
        if (!sampler_) throw std::runtime_error("Cannot allocate translation sampler");
    }
    Engine(const Engine&) = delete;
    Engine& operator=(const Engine&) = delete;
    void cancel() { cancelled_.store(true, std::memory_order_relaxed); }
    const std::string& last_raw_output() const { return last_raw_output_; }
    std::string translate(const std::string& source,
                          const std::function<void(const std::string&)>& stream = {}) {
        check_cancelled();
        last_raw_output_.clear();
        const bool currency_sensitive = mentions_rubles(source);
        const auto prompt = build_prompt(source, kind_);
        const auto* vocab = llama_model_get_vocab(model_.get());
        // HY template already contains BOS; auto-adding one would duplicate it.
        const bool add_special = kind_ == Kind::Lmt;
        int count = llama_tokenize(vocab, prompt.data(), static_cast<int>(prompt.size()), nullptr, 0, add_special, true);
        if (count >= 0) throw std::runtime_error("Unexpected tokenizer size");
        count = -count;
        constexpr int max_output = 512;
        if (count + max_output + 1 > static_cast<int>(llama_n_ctx(ctx_.get()))) {
            throw std::runtime_error("Translation input exceeds context limit; shorten the utterance");
        }
        std::vector<llama_token> tokens(count);
        int actual = llama_tokenize(vocab, prompt.data(), static_cast<int>(prompt.size()), tokens.data(), count, add_special, true);
        if (actual != count) throw std::runtime_error("Translation tokenization failed");
        llama_memory_clear(llama_get_memory(ctx_.get()), true);
        llama_sampler_reset(sampler_.get());
        for (int offset = 0; offset < count; offset += 256) {
            check_cancelled();
            auto batch = llama_batch_get_one(tokens.data() + offset, std::min(256, count - offset));
            if (llama_decode(ctx_.get(), batch) != 0) throw std::runtime_error("Prompt decoding failed or cancelled");
        }
        std::string output;
        for (int i = 0; i < max_output; ++i) {
            check_cancelled();
            auto token = llama_sampler_sample(sampler_.get(), ctx_.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) {
                last_raw_output_ = output;
                auto checked = checked_currency(source, output);
                if (currency_sensitive && stream) stream(checked);
                return checked;
            }
            std::vector<char> piece(256);
            int size = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
            if (size < 0) {
                piece.resize(-size);
                size = llama_token_to_piece(vocab, token, piece.data(), static_cast<int>(piece.size()), 0, false);
            }
            if (size < 0) throw std::runtime_error("Token decoding failed");
            if (size > 0) {
                std::string chunk(piece.data(), size);
                output += chunk;
                if (stream && !currency_sensitive) stream(chunk);
            }
            auto batch = llama_batch_get_one(&token, 1);
            if (llama_decode(ctx_.get(), batch) != 0) throw std::runtime_error("Generation failed or cancelled");
        }
        throw std::runtime_error("Translation output reached 512 tokens; shorten the utterance");
    }
};
} // namespace lmt
