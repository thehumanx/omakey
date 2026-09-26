package dev.omakey.core.locale

/** English's curated contraction table — [LanguageProfile.English]'s `contractions`. Moved out of
 * `AutocorrectIndex` unchanged when language rules became per-profile (AGENTS.md §66 Phase 1); the
 * reasoning for why these are offered but never auto-applied lives on `AutocorrectIndex.contractionFor`. */
object EnglishContractions {
    // Comprehensive coverage of standard English contractions whose apostrophe-less spelling
    // is itself a real word, or which are ambiguous enough that auto-applying would be wrong.
    val map: Map<String, String> = mapOf(
        // I
        "im" to "I'm", "ive" to "I've", "id" to "I'd", "ill" to "I'll",
        // you
        "youre" to "you're", "youve" to "you've", "youd" to "you'd", "youll" to "you'll",
        // he / she / it
        "hes" to "he's", "hed" to "he'd", "hell" to "he'll",
        "shes" to "she's", "shed" to "she'd", "shell" to "she'll",
        "its" to "it's", "itd" to "it'd", "itll" to "it'll",
        // we
        "were" to "we're", "weve" to "we've", "wed" to "we'd", "well" to "we'll",
        // they
        "theyre" to "they're", "theyve" to "they've", "theyd" to "they'd", "theyll" to "they'll",
        // that / who / what / there / here / where / when / why / how
        "thats" to "that's", "thatd" to "that'd", "thatll" to "that'll",
        "whos" to "who's", "whod" to "who'd", "wholl" to "who'll",
        "whats" to "what's", "whatd" to "what'd", "whatll" to "what'll",
        "theres" to "there's", "thered" to "there'd", "therell" to "there'll",
        "heres" to "here's", "wheres" to "where's", "whens" to "when's",
        "whys" to "why's", "hows" to "how's",
        // negatives
        "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't",
        "havent" to "haven't", "hasnt" to "hasn't", "hadnt" to "hadn't",
        "dont" to "don't", "doesnt" to "doesn't", "didnt" to "didn't",
        "wont" to "won't", "cant" to "can't",
        "couldnt" to "couldn't", "shouldnt" to "shouldn't", "wouldnt" to "wouldn't",
        "mightnt" to "mightn't", "mustnt" to "mustn't", "neednt" to "needn't",
        "shant" to "shan't", "oughtnt" to "oughtn't",
        // modal + have — routinely typed without the apostrophe and mistyped on top of that
        "couldve" to "could've", "shouldve" to "should've", "wouldve" to "would've",
        "mightve" to "might've", "mustve" to "must've",
        // let's, y'all, ain't, o'clock
        "lets" to "let's", "yall" to "y'all", "aint" to "ain't", "oclock" to "o'clock",
    )
}
