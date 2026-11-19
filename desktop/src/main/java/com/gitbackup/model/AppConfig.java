package com.gitbackup.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** User settings. Field names match config.json of the PowerShell version. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AppConfig {
    /** Folder that receives the ZIP files. */
    public String destination = "";
    /** How many ZIP versions to keep per repository. */
    public int keepVersions = 3;
    /** Keys (owner/name, or host:owner/name outside GitHub) to back up; empty means every repository. */
    public List<String> include = new ArrayList<>();
    /** Names or patterns (with *) that are never listed or backed up. */
    public List<String> exclude = new ArrayList<>();
    public boolean includeForks = false;
    public boolean includeArchived = true;
    /** Also list repositories of GitHub organisations the account belongs to. */
    public boolean includeOrgs = false;
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
    /** Download a new release in the background before offering it, so that installing does not wait. */
    public boolean downloadUpdatesAhead = true;

    /** GitHub only: put issues, pull requests and their comments into the ZIP as JSON. */
    public boolean backupIssues = false;
    /** GitHub only: put the wiki's full history into the ZIP as a second bundle. */
    public boolean backupWiki = false;
    /** GitHub only: put the release list (names, notes, tags) into the ZIP as JSON. */
    public boolean backupReleases = false;
    /** With {@link #backupReleases}: also download the files attached to each release. */
    public boolean backupReleaseAssets = false;

    /** Address of the GitLab server; gitlab.com unless the account is on a company server. */
    public String gitlabUrl = "https://gitlab.com";
    /** E-mail of the Atlassian account the Bitbucket API token belongs to. */
    public String bitbucketUser = "";

    /** Windows notifications after a run: "always", "problems" or "never". */
    public String notify = "always";

    /** Check the latest ZIP of every repository regularly (all entries readable, history intact). */
    public boolean verifyEnabled = true;
    public int verifyEveryDays = 7;
    /** Date (yyyy-MM-dd) of the last completed check; written by the application. */
    public String lastVerify = "";

    /** Look at the accounts regularly and announce repositories that got new commits. */
    public boolean watchEnabled = true;
    /** Minutes between two looks. */
    public int watchEveryMinutes = 15;
    /** Back up the saved selection by itself when a look finds new commits. */
    public boolean watchAutoBackup = false;

    /** Named groups of repositories, like GitHub lists: list name to repository keys, in the user's order. */
    public Map<String, List<String>> lists = new LinkedHashMap<>();
    /** The list the window shows; empty means every repository. */
    public String shownList = "";
}
