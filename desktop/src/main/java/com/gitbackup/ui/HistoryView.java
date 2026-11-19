package com.gitbackup.ui;

import atlantafx.base.theme.Styles;
import com.gitbackup.model.RunRecord;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;

/** The content of the history window: past runs on top, the repositories of the chosen run below. */
final class HistoryView {
    private final Function<String, String> text;

    HistoryView(Function<String, String> text) {
        this.text = text;
    }

    Node build(List<RunRecord> runs) {
        TableView<RunRecord> runTable = new TableView<>(FXCollections.observableArrayList(runs));
        runTable.getStyleClass().add(Styles.STRIPED);
        runTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        runTable.setPlaceholder(new Label(t("history.empty")));
        runTable.getColumns().add(column("history.when", 150, run -> when(run.started), null, null));
        runTable.getColumns().add(column("history.kind", 200, run -> t("history.kind." + kindKey(run.kind)), null,
                run -> run.kind.contains("check") ? Feather.CHECK_SQUARE : Feather.UPLOAD_CLOUD));
        runTable.getColumns().add(column("history.duration", 80, run -> duration(run), null, null));
        runTable.getColumns().add(column("history.result", 140, this::result, this::resultStyle, this::resultIcon));
        runTable.getColumns().add(column("history.summary", 300, this::summary, null, null));

        TableView<RunRecord.Item> itemTable = new TableView<>();
        itemTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        itemTable.setPlaceholder(new Label(t("history.pick")));
        itemTable.getColumns().add(column("column.repo", 230, item -> item.repo, null, null));
        itemTable.getColumns().add(column("history.outcome", 150, item -> t("outcome." + item.outcome),
                item -> outcomeStyle(item.outcome), item -> outcomeIcon(item.outcome)));
        itemTable.getColumns().add(column("history.detail", 450, item -> item.detail, null, null));

        runTable.getSelectionModel().selectedItemProperty().addListener((observable, before, run) ->
                itemTable.setItems(run == null ? FXCollections.observableArrayList()
                        : FXCollections.observableArrayList(run.items)));
        runTable.getSelectionModel().selectFirst();

        SplitPane split = new SplitPane(runTable, itemTable);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.48);
        split.setPrefSize(900, 560);
        VBox.setVgrow(split, javafx.scene.layout.Priority.ALWAYS);
        return split;
    }

    private String t(String key) {
        return text.apply(key);
    }

    private static String kindKey(String kind) {
        return switch (kind == null ? "" : kind) {
            case "scheduled", "tray", "command", "watch", "check", "scheduled-check" -> kind;
            default -> "manual";
        };
    }

    private String result(RunRecord run) {
        if (!run.ran) {
            return t("history.result.notStarted");
        }
        if (run.stopped) {
            return t("history.result.stopped");
        }
        return t(run.exitCode == 0 ? "history.result.ok" : "history.result.problems");
    }

    private String resultStyle(RunRecord run) {
        if (!run.ran) {
            return "status-missing";
        }
        return run.exitCode == 0 && !run.stopped ? "status-current" : "status-pending";
    }

    private Ikon resultIcon(RunRecord run) {
        if (!run.ran) {
            return Feather.X_CIRCLE;
        }
        return run.exitCode == 0 && !run.stopped ? Feather.CHECK_CIRCLE : Feather.ALERT_TRIANGLE;
    }

    private String summary(RunRecord run) {
        if (!run.ran) {
            return run.error;
        }
        if (run.kind != null && run.kind.contains("check")) {
            return MessageFormat.format(t("history.summary.check"), run.checked - run.damaged, run.damaged,
                    run.skipped);
        }
        return MessageFormat.format(t("history.summary.backup"), run.backedUp, run.unchanged, run.failed,
                run.removed);
    }

    private static String outcomeStyle(String outcome) {
        return switch (outcome) {
            case "BACKED_UP", "OK" -> "status-current";
            case "FAILED", "DAMAGED" -> "status-missing";
            case "SKIPPED" -> "status-pending";
            default -> null;
        };
    }

    private static Ikon outcomeIcon(String outcome) {
        return switch (outcome) {
            case "BACKED_UP" -> Feather.UPLOAD_CLOUD;
            case "OK" -> Feather.CHECK_CIRCLE;
            case "FAILED", "DAMAGED" -> Feather.ALERT_TRIANGLE;
            case "SKIPPED" -> Feather.MINUS_CIRCLE;
            default -> Feather.CHECK;
        };
    }

    /** yyyy-MM-ddTHH:mm:ss becomes yyyy-MM-dd  HH:mm, kept left to right. */
    private static String when(String iso) {
        if (iso == null || iso.length() < 16) {
            return iso == null ? "" : iso;
        }
        return "‪" + iso.substring(0, 10) + "  " + iso.substring(11, 16) + "‬";
    }

    private static String duration(RunRecord run) {
        try {
            Duration length = Duration.between(LocalDateTime.parse(run.started), LocalDateTime.parse(run.finished));
            long seconds = Math.max(0, length.getSeconds());
            return "‪" + String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60) + "‬";
        } catch (DateTimeParseException | NullPointerException e) {
            return "";
        }
    }

    private static <S> TableColumn<S, String> column(String title, double width, Function<S, String> value,
                                                     Function<S, String> style, Function<S, Ikon> icon,
                                                     Function<String, String> text) {
        TableColumn<S, String> column = new TableColumn<>(text.apply(title));
        column.setPrefWidth(width);
        column.setSortable(false);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("status-current", "status-pending", "status-missing");
                S row = empty || getTableRow() == null ? null : getTableRow().getItem();
                if (row == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(item);
                Ikon ikon = icon == null ? null : icon.apply(row);
                setGraphic(ikon == null ? null : Icons.icon(ikon, 14));
                setGraphicTextGap(6);
                String styleClass = style == null ? null : style.apply(row);
                if (styleClass != null) {
                    getStyleClass().add(styleClass);
                }
            }
        });
        return column;
    }

    private <S> TableColumn<S, String> column(String title, double width, Function<S, String> value,
                                              Function<S, String> style, Function<S, Ikon> icon) {
        return column(title, width, value, style, icon, text);
    }
}
