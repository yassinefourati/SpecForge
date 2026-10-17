package io.specforge.gen;

public class GeneratedFile {
    public String id = "";
    public String layer = "";
    public String language = "text";
    /** Path shown in the UI / used inside the ZIP (relative to the target dir when possible). */
    public String path = "";
    /** Absolute destination on disk. */
    public String absPath = "";
    public String content = "";
    /** NEW | EXISTS | IDENTICAL */
    public String status = "NEW";
}
