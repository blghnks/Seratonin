package com.fungisoft.seratonin;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Utility class for normalizing strings to handle encoding issues and Unicode variations.
 * This helps group albums/artists correctly when metadata contains unusual characters
 * (e.g., curly apostrophes, mojibake like "D'une" appearing differently encoded).
 */
public class StringNormalizer {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    
    // Non-ASCII characters that should be replaced
    private static final Pattern NON_BASIC_CHARS = Pattern.compile("[^\\p{ASCII}]");

    /**
     * Normalize a string for comparison purposes.
     * This handles:
     * - Unicode normalization (NFD form)
     * - Removing diacritics (accents)
     * - Replacing non-ASCII punctuation with ASCII equivalents
     * - Case-insensitive comparison
     * - Trimming whitespace
     * 
     * @param input The string to normalize
     * @return A normalized string suitable for comparison, or empty string if input is null
     */
    public static String normalizeForComparison(String input) {
        if (input == null) {
            return "";
        }
        
        String result = input;
        
        // First, normalize to NFD (decomposed form) to separate base chars from diacritics
        result = Normalizer.normalize(result, Normalizer.Form.NFD);
        
        // Remove diacritical marks (accents)
        result = DIACRITICS.matcher(result).replaceAll("");
        
        // Replace common Unicode punctuation variants with ASCII
        result = replaceUnicodePunctuation(result);
        
        // Remove any remaining non-ASCII characters (mojibake sequences)
        result = NON_BASIC_CHARS.matcher(result).replaceAll("");
        
        // Convert to lowercase and trim
        result = result.toLowerCase().trim();
        
        // Collapse multiple spaces into one
        result = result.replaceAll("\\s+", " ");
        
        return result;
    }
    
    /**
     * Replace Unicode punctuation variants with their ASCII equivalents.
     */
    private static String replaceUnicodePunctuation(String input) {
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                // Curly single quotes and apostrophe variants
                case '\u2018': // LEFT SINGLE QUOTATION MARK
                case '\u2019': // RIGHT SINGLE QUOTATION MARK
                case '\u201A': // SINGLE LOW-9 QUOTATION MARK
                case '\u201B': // SINGLE HIGH-REVERSED-9 QUOTATION MARK
                case '\u0060': // GRAVE ACCENT
                case '\u00B4': // ACUTE ACCENT
                case '\u02BC': // MODIFIER LETTER APOSTROPHE
                    sb.append('\'');
                    break;
                    
                // Curly double quotes
                case '\u201C': // LEFT DOUBLE QUOTATION MARK
                case '\u201D': // RIGHT DOUBLE QUOTATION MARK
                case '\u201E': // DOUBLE LOW-9 QUOTATION MARK
                case '\u201F': // DOUBLE HIGH-REVERSED-9 QUOTATION MARK
                    sb.append('"');
                    break;
                    
                // Dash variants
                case '\u2013': // EN DASH
                case '\u2014': // EM DASH
                case '\u2015': // HORIZONTAL BAR
                    sb.append('-');
                    break;
                    
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }
}
