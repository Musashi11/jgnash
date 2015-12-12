/*
 * jGnash, a personal finance application
 * Copyright (C) 2001-2015 Craig Cavanaugh
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package jgnash.uifx.report;

import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;

import javax.imageio.ImageIO;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXML;
import javafx.geometry.Side;
import javafx.print.PageLayout;
import javafx.print.PageOrientation;
import javafx.print.Printer;
import javafx.print.PrinterJob;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.chart.PieChart;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.transform.Scale;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import jgnash.engine.Account;
import jgnash.engine.AccountType;
import jgnash.engine.CurrencyNode;
import jgnash.engine.Engine;
import jgnash.engine.EngineFactory;
import jgnash.resource.cursor.CustomCursor;
import jgnash.text.CommodityFormat;
import jgnash.uifx.Options;
import jgnash.uifx.StaticUIMethods;
import jgnash.uifx.control.AccountComboBox;
import jgnash.uifx.control.DatePickerEx;
import jgnash.uifx.util.InjectFXML;
import jgnash.uifx.views.main.MainApplication;
import jgnash.util.FileUtils;
import jgnash.util.function.ParentAccountPredicate;

/**
 * Income and Expense Pie Chart
 *
 * @author Craig Cavanaugh
 */
public class IncomeExpenseDialogController {

    /**
     * Increases scale of captured image.  This could be made user configurable
     */
    private static final int SNAPSHOT_SCALE_FACTOR = 4;

    @InjectFXML
    private final ObjectProperty<Scene> parentProperty = new SimpleObjectProperty<>();

    @FXML
    private PieChart pieChart;

    @FXML
    private DatePickerEx startDatePicker;

    @FXML
    private DatePickerEx endDatePicker;

    @FXML
    private AccountComboBox accountComboBox;

    @FXML
    private ResourceBundle resources;

    private boolean nodeFocused = false;

    private static final String LAST_ACCOUNT = "lastAccount";

    @FXML
    public void initialize() {

        // Respect animation preference
        pieChart.animatedProperty().setValue(Options.animationsEnabledProperty().get());

        accountComboBox.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && newValue.getParent().getAccountType() != AccountType.ROOT) {
                pieChart.setCursor(CustomCursor.getZoomOutCursor());
            } else {
                pieChart.setCursor(Cursor.DEFAULT);
            }
        });

        final Preferences preferences = Preferences.userNodeForPackage(IncomeExpenseDialogController.class);

        accountComboBox.setPredicate(new ParentAccountPredicate());

        if (preferences.get(LAST_ACCOUNT, null) != null) {
            final Engine engine = EngineFactory.getEngine(EngineFactory.DEFAULT);
            Objects.requireNonNull(engine);

            final Account account = engine.getAccountByUuid(preferences.get(LAST_ACCOUNT, null));

            if (account != null) {
                accountComboBox.setValue(account);
            }
        }

        startDatePicker.setValue(endDatePicker.getValue().minusYears(1));

        final ChangeListener<Object> listener = (observable, oldValue, newValue) -> {
            if (newValue != null) {
                updateChart();
                preferences.put(LAST_ACCOUNT, accountComboBox.getValue().getUuid());
            }
        };

        accountComboBox.valueProperty().addListener(listener);
        startDatePicker.valueProperty().addListener(listener);
        endDatePicker.valueProperty().addListener(listener);

        pieChart.setLegendSide(Side.RIGHT);

        // zoom out
        pieChart.setOnMouseClicked(event -> {
            if (!nodeFocused && accountComboBox.getValue().getParent().getAccountType() != AccountType.ROOT) {
                accountComboBox.setValue(accountComboBox.getValue().getParent());
            }
        });

        updateChart();
    }

    private void updateChart() {
        final Engine engine = EngineFactory.getEngine(EngineFactory.DEFAULT);
        Objects.requireNonNull(engine);

        Account a = accountComboBox.getValue();

        if (a != null) {
            final CurrencyNode defaultCurrency = a.getCurrencyNode();

            final NumberFormat numberFormat = CommodityFormat.getFullNumberFormat(defaultCurrency);

            final ObservableList<PieChart.Data> pieChartData = FXCollections.observableArrayList();

            double total = a.getTreeBalance(startDatePicker.getValue(), endDatePicker.getValue(), defaultCurrency).doubleValue();

            for (final Account child : a.getChildren()) {
                double balance = child.getTreeBalance(startDatePicker.getValue(), endDatePicker.getValue(), defaultCurrency).doubleValue();

                if (balance > 0 || balance < 0) {
                    final String label = child.getName() + " - " + numberFormat.format(balance);
                    final PieChart.Data data = new PieChart.Data(label, balance / total * 100);

                    // nodes are created lazily.  Set the user data (Account) after the node is created
                    data.nodeProperty().addListener((observable, oldValue, newValue) -> {
                        newValue.setUserData(child);
                    });

                    pieChartData.add(data);
                }
            }

            pieChart.setData(pieChartData);

            final NumberFormat percentFormat = NumberFormat.getPercentInstance();
            percentFormat.setMaximumFractionDigits(1);
            percentFormat.setMinimumFractionDigits(1);

            // Install tooltips on the data after it has been added to the chart
            pieChart.getData().stream().forEach(data ->
                    Tooltip.install(data.getNode(), new Tooltip(percentFormat.format(data.getPieValue() / 100d))));

            // Indicate the node can be clicked on to zoom into the next account level
            for (final PieChart.Data data : pieChart.getData()) {
                data.getNode().setOnMouseEntered(event -> {
                    final Account account = (Account) data.getNode().getUserData();
                    if (account.isParent()) {
                        data.getNode().setCursor(CustomCursor.getZoomInCursor());
                    } else {
                        data.getNode().setCursor(Cursor.DEFAULT);
                    }

                    nodeFocused = true;
                });

                data.getNode().setOnMouseExited(event -> nodeFocused = false);

                // zoom in on click if this is a parent account
                data.getNode().setOnMouseClicked(event -> {
                    if (data.getNode().getUserData() != null) {
                        if (((Account) data.getNode().getUserData()).isParent()) {
                            accountComboBox.setValue((Account) data.getNode().getUserData());
                        }
                    }
                });
            }

            final String title;

            // pick an appropriate title
            if (a.getAccountType() == AccountType.EXPENSE) {
                title = resources.getString("Title.PercentExpense");
            } else if (a.getAccountType() == AccountType.INCOME) {
                title = resources.getString("Title.PercentIncome");
            } else {
                title = resources.getString("Title.PercentDist");
            }

            pieChart.setTitle(title + " - " + accountComboBox.getValue().getName() + " - " + numberFormat.format(total));

            // abs() on all values won't work if children aren't of uniform sign,
            // then again, this chart is not right to display those trees
            //boolean negate = total != null && total.signum() < 0;
        } else {
            pieChart.setData(FXCollections.emptyObservableList());
            pieChart.setTitle("No Data");
        }
    }

    private WritableImage takeSnapshot() {
        // Need to disable animation for printing
        pieChart.animatedProperty().setValue(false);

        final SnapshotParameters snapshotParameters = new SnapshotParameters();
        snapshotParameters.setTransform(new Scale(SNAPSHOT_SCALE_FACTOR, SNAPSHOT_SCALE_FACTOR));

        final WritableImage image = pieChart.snapshot(snapshotParameters, null);

        // Restore animation
        pieChart.animatedProperty().setValue(Options.animationsEnabledProperty().get());

        return image;
    }

    @FXML
    private void handleSaveAction() {
        final FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(resources.getString("Title.SaveFile"));
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("PNG", "*.png")
        );

        final File file = fileChooser.showSaveDialog(MainApplication.getInstance().getPrimaryStage());

        if (file != null) {
            final WritableImage image = takeSnapshot();

            try {
                final String type = FileUtils.getFileExtension(file.toString().toLowerCase(Locale.ROOT));

                ImageIO.write(SwingFXUtils.fromFXImage(image, null), type, file);
            } catch (final IOException e) {
                StaticUIMethods.displayException(e);
            }
        }
    }

    @FXML
    private void handleCopyToClipboard() {
        final Clipboard clipboard = Clipboard.getSystemClipboard();
        final ClipboardContent content = new ClipboardContent();

        content.putImage(takeSnapshot());

        clipboard.setContent(content);
    }

    @FXML
    private void handlePrintAction() {
        // Manipulate a snapshot of the chart instead of the chart itself to avoid visual artifacts when scaling
        final ImageView imageView = new ImageView(takeSnapshot());

        final PrinterJob job = PrinterJob.createPrinterJob();

        if (job != null) {

            // Get the default page layout
            final Printer printer = Printer.getDefaultPrinter();
            PageLayout pageLayout = job.getJobSettings().getPageLayout();

            // Request landscape orientation by default
            pageLayout = printer.createPageLayout(pageLayout.getPaper(), PageOrientation.LANDSCAPE,
                    Printer.MarginType.DEFAULT);

            job.getJobSettings().setPageLayout(pageLayout);

            if (job.showPageSetupDialog(MainApplication.getInstance().getPrimaryStage())) {
                pageLayout = job.getJobSettings().getPageLayout();

                // determine the scaling factor to fit the page
                double scale = Math.min(pageLayout.getPrintableWidth() / imageView.getBoundsInParent().getWidth(),
                        pageLayout.getPrintableHeight() / imageView.getBoundsInParent().getHeight());

                imageView.getTransforms().add(new Scale(scale, scale));

                if (job.printPage(imageView)) {
                    job.endJob();
                }
            } else {
                job.cancelJob();
            }
        }
    }

    @FXML
    private void handleCloseAction() {
        ((Stage) parentProperty.get().getWindow()).close();
    }
}