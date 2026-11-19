Synthetic javac switch-map holders compiled locally with JDK17/21/25 during R18 research.
Input: p.Channel { BANK,CARD,WALLET }, p.Mode { ALPHA,BETA }; Svc switches on Mode ALPHA/BETA and Channel WALLET/BANK/CARD.
Base64 preserves the original compiler bytecode, including exception-table labels, for strict parser compatibility tests. No application/customer code.
