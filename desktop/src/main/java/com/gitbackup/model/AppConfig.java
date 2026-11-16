package com.gitbackup.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/** User settings. Field names match config.json of the PowerShell version. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfig {
    /** Folder that receives the ZIP files. */
    public String destination = "";
    /** How many ZIP versions to keep per repository. */
    public int keepVersions = 3;
    /** Full names (owner/name) to back up; empty means every repository. */
    public List<String> include = new ArrayList<>();
    /** Names or patterns (with *) that are never listed or backed up. */
    public List<String> exclude = new ArrayList<>();
    public boolean includeForks = false;
    public boolean includeArchived = true;
    /** Protect new ZIP files with the saved password (AES-256). */
    public boolean encrypt = false;
    /** Interface language: "ar" or "en". */
    public String language = "ar";
}
