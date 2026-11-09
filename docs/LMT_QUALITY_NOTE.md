# LMT quality finding: ruble mistranslation

The first real-weight run (42612590316, commit 7b024decbe54d02da30bd3604dc09c1a1f1ba75e) compiled successfully and passed 17 JVM unit tests plus its initial number/CJK smoke gates. Manual review found that those gates were insufficient for translation quality.

Raw model output:

- `Здравствуйте. Мы хотим купить 300 тонн кукурузы.` → `你好，我们想买300吨玉米。`
- `Цена составляет 1000 рублей за тонну.` → `每吨价格为 1000 元。`

The second translation **does not preserve the currency**. A passing number-preservation test is not translation acceptance.

## Runtime mitigation

The shared Android/host C++ core now recognizes common ruble words, abbreviations and the currency symbol. For those utterances it holds streaming output until completion and checks for a ruble designation in the translated output. When missing, it publishes an explicit `[LMT 币种校验未通过：原文含卢布，译文未保留币种，请核对原文。]` warning instead of a misleading translation. It never guesses an amount/currency conversion or pretends the model itself translated correctly.

This is a narrow known-error guard. It does NOT establish that amounts, units, multiple currencies, negation or business meaning are always correct. It does not fix the model weights. Business/contract translation still requires human review.

The real-model CI retains the failing raw sample, checks that the incorrect final/streaming output is withheld, and reports **runtime_and_guard_passed** separately from **quality_accepted**. A green build is not a claim that model quality has been accepted.

The model was actually downloaded and hashed: 1,282,439,264 bytes, SHA-256 `54c6244f100330264cef607188413ee82cb8a254f0cf950011aaa9cee74f7eb5`.
