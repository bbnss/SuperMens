package it.supermens.local

/** Italian is the only exception to the English fallback, including unsupported device languages. */
object LanguageChoice {
    fun code(tag: String): String = if(tag.equals("italiano",true) || tag.equals("Italian",true) || tag.equals("it",true) || tag.startsWith("it-",true) || tag.startsWith("it_",true)) "it" else "en"
    fun text(tag: String, italian: String, english: String): String = if(code(tag)=="it") italian else english
}
