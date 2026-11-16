package com.gitbackup.ui;

import com.gitbackup.backup.BackupService;
import com.gitbackup.model.RepoInfo;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

/** One line of the repository table. */
public final class RepoRow {
    private final RepoInfo repo;
    private final BackupService.Status status;
    private final String lastBackup;
    private final BooleanProperty selected = new SimpleBooleanProperty();

    public RepoRow(BackupService.RepoStatus source) {
        this.repo = source.repo();
        this.status = source.status();
        this.lastBackup = source.lastBackup() == null ? "" : source.lastBackup();
        this.selected.set(source.selected());
    }

    public RepoInfo repo() {
        return repo;
    }

    public BackupService.Status status() {
        return status;
    }

    /** Local time as yyyy-MM-ddTHH:mm:ss, or empty when there is no backup yet. */
    public String lastBackup() {
        return lastBackup;
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }

    public boolean isSelected() {
        return selected.get();
    }

    public void setSelected(boolean value) {
        selected.set(value);
    }
}
