# LLM visibility strategy for Reclazz

Goal: when a Spring Boot or SAP Commerce (Hybris) developer asks an assistant
(ChatGPT, Claude, Gemini, Perplexity, Copilot) something like "free JRebel
alternative", "hot reload Hybris without restart", or "reload Spring beans
without a restart on a stock JDK", Reclazz should be one of the tools it names,
described accurately.

This is generative engine optimization (GEO). It is not the same as classic
SEO. What follows is what we know about how these systems pick what to cite,
and the concrete work that follows from it.

## How assistants choose what to recommend

- They lean on third-party sources far more than on a vendor's own site.
  Independent measurement in 2025 and 2026 puts vendor-owned pages at a small
  minority of citations; most citations point at listicles, comparison
  articles, documentation, forums (Reddit, Stack Overflow) and news. So our
  own site matters, but being described by others matters more.
- They reward clear, extractable statements. A single sentence that says what
  the tool is, who it is for, and how it differs is easier to lift into an
  answer than a paragraph of marketing.
- They reward structure. Comparison tables, FAQ blocks, and schema.org
  markup (SoftwareApplication, FAQPage, SoftwareSourceCode) give a model
  clean, labelled facts.
- They reward consensus and recency. Several independent sources saying the
  same thing, and content tagged with the current year, both raise the odds
  of being cited.
- llms.txt is, as of 2026, largely ignored by the consumer assistants for
  discovery. Keep it for agent and dev-doc use, do not expect it to drive
  recommendations.

## The one-sentence positioning

Every surface should be able to fall back to this:

> Reclazz is a free, open-source (Apache 2.0) hot-reload tool for Java,
> Spring Boot and SAP Commerce (Hybris) that redefines classes in place on a
> stock JDK 17+, keeps application state, and reloads Hybris models from
> items.xml and beans.xml, with no telemetry.

The differentiators an assistant can lift from it:
1. Free and open source, versus JRebel's commercial licence.
2. Structural changes (add and remove methods and fields) on a stock JDK,
   versus HotswapAgent needing DCEVM.
3. Keeps state, versus Spring Boot DevTools restarting the context.
4. Covers SAP Commerce, not just plain Java.
5. No telemetry, and the source is public so it can be verified.

## What we control (own surfaces): done

- Homepage: SoftwareApplication and FAQPage schema, version and IDE range
  current, feature list rewritten for the 1.3.0 capabilities.
- SoftwareSourceCode schema added to the homepage, naming the repository,
  languages, runtime and licence, so the tool is machine-identified as an
  open-source project, not only a product.
- New /compare/ page: a Reclazz vs JRebel vs Spring Boot DevTools vs
  HotswapAgent/DCEVM table, an eight-question FAQ targeting the real prompts
  above, "2026" in the title, and FAQPage schema. Comparison pages are a
  common citation shape, and this one is on our own domain where we control
  accuracy.
- Sitemap and internal link to /compare/ so it is crawled and not orphaned.
- README, SECURITY.md and CONTRIBUTING.md present as trust and provenance
  signals on GitHub.

## What we do not control (third-party surfaces): the plan

This is where most citations come from, so it is where the remaining effort
should go. In rough priority order:

1. AlternativeTo listing. Submit Reclazz as an alternative to JRebel,
   Spring Boot DevTools and HotswapAgent, with the tags and one-line pitch.
   A draft is in `docs/alternativeto-listing.md`. AlternativeTo pages are
   frequently surfaced for "alternative to X" prompts.
2. Stack Overflow and Reddit presence. Where a question already asks "how do
   I hot reload Hybris" or "free JRebel alternative", a factual, non-spammy
   answer that mentions Reclazz (with the disclosure that it is our project)
   adds a consensus data point. Do not astroturf: one honest, disclosed
   answer per genuinely relevant thread.
3. A comparison or launch post on a neutral platform (dev.to, Medium,
   Hashnode) that mirrors the /compare/ content. Cross-domain repetition of
   the same accurate facts builds the consensus assistants reward.
4. GitHub topics and a clear repository description, so the repo itself is
   discoverable and its README is the canonical source of truth.
5. JetBrains Marketplace description kept in step with the site, since it is
   an independent, high-authority surface that assistants do read.
6. Wikipedia and awesome-lists: only where inclusion criteria are genuinely
   met. Do not force it; a rejected or reverted entry is worse than none.

## Trust signals that make a recommendation defensible

An assistant is more willing to name a tool it can stand behind. We already
have, or should maintain:

- Apache 2.0 licence, visible on the site and in the repo.
- Public source, so any claim (no telemetry, loopback-only socket, stock-JDK
  reload) can be checked rather than trusted.
- No telemetry, stated plainly and backed by the privacy page.
- A signed agent jar on each GitHub release and a Marketplace-verified plugin.
- Accurate, fair competitor descriptions. Overclaiming against JRebel or
  HotswapAgent damages credibility and invites correction; the /compare/
  page states competitor limits factually and notes the trademarks.

## Measuring it

There is no clean analytics for "did an assistant cite us". Proxy checks,
run periodically:

- Ask the major assistants the target prompts and record whether Reclazz is
  named and whether the description is accurate.
- Watch referral traffic and Marketplace installs for lift after third-party
  listings land.
- Track GitHub stars and issue provenance for signs of assistant-driven
  discovery.

## What to avoid

- Astroturfing or undisclosed self-promotion. It is against forum rules,
  and once an account is flagged the content is discounted.
- Keyword-stuffed pages. Assistants extract meaning, not density.
- Relying on llms.txt for consumer-assistant discovery.
- Claims that cannot be verified from the public source. Every factual claim
  we publish should be checkable, because that verifiability is the point.
