package com.gitbackup.backup;

import com.gitbackup.model.RepoInfo;
import java.nio.file.Path;
import java.util.List;

/** Data outside git that also goes into a repository's ZIP, such as issues and releases. */
public interface ExtrasSource {
    /** A file prepared in a work folder and its name inside the ZIP. */
    record ExtraFile(String nameInZip, Path file) {
    }

    /**
     * Text that changes whenever the extra data changes, so an otherwise unchanged repository
     * is backed up again. Empty when there is nothing extra for this repository.
     */
    String fingerprint(RepoInfo repo) throws Exception;

    /** Writes the extra files into {@code workDir}. */
    List<ExtraFile> collect(RepoInfo repo, Path workDir, BackupLog log) throws Exception;
}
