package au.org.ala.bie.util

/**
 * A very simple title capitaliser that just capitalises the first letter and lowercases the rest.
 * It follows the unwritten rule of VBP 'capitalize the first letter of the first word and lowercase the rest'.
 * It also ignores language and locale as those are not relevant for this use case
 * Examples:
 *  * "Kleine Bonte Specht" -> "Kleine bonte specht"
 *  * "blauwe reiger" -> "Blauwe reiger"
 *  * "Turkse Tortel" -> "Turkse tortel"
 *  * "huiskat" -> "Huiskat"
 */
class VBPTitleCapitaliser {
    static String capitalise(String input){
        if (!input) {
            return input
        }

        input = input.trim()
        if (input.isEmpty()) {
            return input
        }

        return input.substring(0, 1).toUpperCase() + input.substring(1).toLowerCase()
    }
}
