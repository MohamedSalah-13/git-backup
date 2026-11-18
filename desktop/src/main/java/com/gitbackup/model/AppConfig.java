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
    /** Run the backup by itself once a day while the application is open or in the tray. */
    public boolean scheduleEnabled = false;
    /** Time of the daily run, HH:mm (24-hour). */
    public String scheduleTime = "21:00";
    /** Date (yyyy-MM-dd) of the last automatic run that started; written by the application. */
    public String lastAutoRun = "";
    /** Interface language: "ar" or "en". */
    public String language = "ar";
    /** Window theme: "system" (follows Windows), "light" or "dark". */
    public String theme = "system";
}
