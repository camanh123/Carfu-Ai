package org.stypox.dicio.aliasnormalizer

/**
 * Tiny text harness for pasting real CARFU STT transcripts.
 * No microphone. No Android. No network.
 *
 * Run:
 *   ./gradlew :mixed-language-alias-normalizer:normalizeTranscript --args="Mở bài X trên sờ mát túp"
 *   echo 'Mở bài X trên sờ mát túp' | ./gradlew :mixed-language-alias-normalizer:normalizeTranscript --console=plain
 */
fun main(args: Array<String>) {
    val input = if (args.isNotEmpty()) {
        args.joinToString(" ").trim()
    } else {
        generateSequence { readLine() }.joinToString("\n").trim()
    }
    if (input.isEmpty()) {
        System.err.println("Usage: provide a transcript as args or stdin.")
        kotlin.system.exitProcess(1)
    }
    val normalizer = DefaultProviderAliasNormalizer()
    val result = normalizer.normalize(input)
    val resolved = normalizer.resolve(input)
    println("INPUT TRANSCRIPT")
    println(result.originalTranscript)
    println("NORMALIZED TRANSCRIPT")
    println(result.normalizedTranscript)
    println("PROVIDER DETECTED")
    println(result.providerDetected ?: "")
    println("ALIAS MATCHED")
    println(result.aliasMatched ?: "")
    println("CHANGED")
    println(result.changed)
    println("CANDIDATE")
    println(resolved.originalCandidate ?: "")
    println("EXACT_ALIAS_MATCH")
    println(resolved.exactAliasMatch)
    println("LEXICAL")
    println(resolved.lexicalScore)
    println("PHONETIC")
    println(resolved.phoneticScore)
    println("FINAL")
    println(resolved.finalScore)
    println("CONFIDENCE")
    println(resolved.confidence)
    println("MATCH_TYPE")
    println(resolved.matchType)
    println("AMBIGUOUS")
    println(resolved.ambiguous)
    println("SHOULD_REWRITE")
    println(resolved.shouldRewrite)
    println("REASON")
    println(resolved.reason)
}
