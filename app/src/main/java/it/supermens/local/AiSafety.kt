// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.
package it.supermens.local

internal class AiResponseRejected:IllegalStateException()

/** Conservative extra guard for explicit EN/IT chat requests, not a moderation certification. */
internal object AiSafety {
    private val explicitSex=Regex("\\b(sexual|pornographic|pornograf\\w*|sessual\\w*|nude|nud[ioe])\\b")
    private val minors=Regex("\\b(child(?:ren)?|minor(?:s|i|e)?|bambin\\w*|underage)\\b")
    private val solicitation=Regex("\\b(create|generate|write|show|crea|genera|scrivi|mostra)\\b")
    private val violentInstructions=Regex("(?:how to|step.by.step|instructions? (?:for|to)|come|istruzioni per).{0,100}(?:build a bomb|make a bomb|costruire una bomba|fare una bomba|kill someone|uccidere qualcuno)")
    fun blocked(text:String):Boolean {
        val normalized=java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT)
            .replace(Regex("\\s+")," ")
        return (explicitSex.containsMatchIn(normalized) && minors.containsMatchIn(normalized) && solicitation.containsMatchIn(normalized)) ||
            violentInstructions.containsMatchIn(normalized)
    }
}
