/**
 * 
 */
package com.flowingcode.vaadin.addons.gridexporter;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellAddress;
import org.apache.poi.ss.util.CellRangeAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid.Column;
import com.vaadin.flow.component.grid.dataview.GridLazyDataView;
import com.vaadin.flow.data.binder.BeanPropertySet;
import com.vaadin.flow.data.binder.PropertySet;
import com.vaadin.flow.data.provider.AbstractBackEndDataProvider;
import com.vaadin.flow.data.provider.DataCommunicator;
import com.vaadin.flow.data.provider.DataProvider;
import com.vaadin.flow.data.provider.Query;

/**
 * @author mlope
 *
 */
@SuppressWarnings("serial")
class ExcelInputStreamFactory<T> extends BaseInputStreamFactory<T> {

  private final static Logger LOGGER = LoggerFactory.getLogger(ExcelInputStreamFactory.class);
  private static final String DEFAULT_TEMPLATE = "/template.xlsx";

  public ExcelInputStreamFactory(GridExporter<T> exporter, String template) {
    super(exporter, template, DEFAULT_TEMPLATE);
  }

  @Override
  public InputStream createInputStream() {
    PipedInputStream in = new PipedInputStream();
    try {
      exporter.columns = exporter.grid.getColumns().stream().filter(this::isExportable)
          .collect(Collectors.toList());
      Workbook wb = getBaseTemplateWorkbook();
      Sheet sheet = wb.getSheetAt(exporter.sheetNumber);

      Cell titleCell = findCellWithPlaceHolder(sheet, exporter.titlePlaceHolder);
      if (titleCell != null) {
        titleCell.setCellValue(exporter.title);
      }

      Cell cell = findCellWithPlaceHolder(sheet, exporter.headersPlaceHolder);
      List<Pair<String, Column<T>>> headers = getGridHeaders(exporter.grid);

      fillHeaderOrFooter(sheet, cell, headers, true);
      if (exporter.autoMergeTitle && titleCell != null) {
        sheet.addMergedRegion(new CellRangeAddress(titleCell.getRowIndex(), titleCell.getRowIndex(),
            titleCell.getColumnIndex(), titleCell.getColumnIndex() + headers.size() - 1));
      }

      cell = findCellWithPlaceHolder(sheet, exporter.dataPlaceHolder);
      int[] dataStartingColumn = new int[1];
      dataStartingColumn[0] = cell.getColumnIndex();
      fillData(sheet, cell, exporter.grid.getDataProvider(), titleCell != null);

      cell = findCellWithPlaceHolder(sheet, exporter.footersPlaceHolder);
      List<Pair<String, Column<T>>> footers = getGridFooters(exporter.grid);
      if (cell != null) {
        fillHeaderOrFooter(sheet, cell, footers, false);
      }

      if (exporter.isAutoSizeColumns()) {
        exporter.columns.forEach(column -> {
          sheet.autoSizeColumn(dataStartingColumn[0]);
          dataStartingColumn[0]++;
        });
      }

      exporter.additionalPlaceHolders.entrySet().forEach(entry -> {
        Cell cellwp;
        cellwp = findCellWithPlaceHolder(sheet, entry.getKey());
        while (cellwp != null) {
          cellwp.setCellValue(entry.getValue());
          cellwp = findCellWithPlaceHolder(sheet, entry.getKey());
        }
      });

      final PipedOutputStream out = new PipedOutputStream(in);
      new Thread(new Runnable() {
        public void run() {
          try {
            wb.write(out);
          } catch (IOException e) {
            LOGGER.error("Problem generating export", e);
          } finally {
            if (out != null) {
              try {
                out.close();
              } catch (IOException e) {
                LOGGER.error("Problem generating export", e);
              }
            }
          }
        }
      }).start();
    } catch (IOException e) {
      LOGGER.error("Problem generating export", e);
    }
    return in;
  }


  @SuppressWarnings("unchecked")
  private void fillData(Sheet sheet, Cell dataCell, DataProvider<T, ?> dataProvider,
      boolean titleExists) {
    Object filter = null;
    try {
      Method method = DataCommunicator.class.getDeclaredMethod("getFilter");
      method.setAccessible(true);
      filter = method.invoke(exporter.grid.getDataCommunicator());
    } catch (Exception e) {
      LOGGER.error("Unable to get filter from DataCommunicator", e);
    }

    Stream<T> dataStream;
    if (dataProvider instanceof AbstractBackEndDataProvider) {
      GridLazyDataView<T> gridLazyDataView = exporter.grid.getLazyDataView();
      dataStream = gridLazyDataView.getItems();
    } else {
      @SuppressWarnings("rawtypes")
      Query<T, ?> streamQuery =
          new Query<>(0, exporter.grid.getDataProvider().size(new Query(filter)),
              exporter.grid.getDataCommunicator().getBackEndSorting(),
              exporter.grid.getDataCommunicator().getInMemorySorting(), null);
      dataStream = getDataStream(streamQuery);
    }

    boolean[] notFirstRow = new boolean[1];
    Cell[] startingCell = new Cell[1];
    startingCell[0] = dataCell;
    dataStream.forEach(t -> {
      if (notFirstRow[0]) {
        CellStyle cellStyle = startingCell[0].getCellStyle();
        int lastRow = sheet.getLastRowNum();
        sheet.shiftRows(startingCell[0].getRowIndex() + (titleExists ? 1 : 0), lastRow,
            (titleExists ? 1 : 0));
        Row newRow = sheet.createRow(startingCell[0].getRowIndex() + 1);
        startingCell[0] = newRow.createCell(startingCell[0].getColumnIndex());
        startingCell[0].setCellStyle(cellStyle);
      }
      buildRow(t, sheet, startingCell[0]);
      notFirstRow[0] = true;
    });

  }

  @SuppressWarnings("unchecked")
  private void buildRow(T item, Sheet sheet, Cell startingCell) {
    if (exporter.propertySet == null) {
      exporter.propertySet = (PropertySet<T>) BeanPropertySet.get(item.getClass());
    }
    if (exporter.columns.isEmpty())
      throw new IllegalStateException("Grid has no columns");

    int[] currentColumn = new int[1];
    currentColumn[0] = startingCell.getColumnIndex();
    exporter.columns.forEach(column -> {
      Object value = exporter.extractValueFromColumn(item, column);
      value = transformToType(value, column);
      Cell currentCell = startingCell;
      if (startingCell.getColumnIndex() < currentColumn[0]) {
        currentCell = startingCell.getRow().createCell(currentColumn[0]);
        CellStyle newCellStyle = currentCell.getSheet().getWorkbook().createCellStyle();
        newCellStyle.cloneStyleFrom(startingCell.getCellStyle());
        currentCell.setCellStyle(newCellStyle);

        configureAlignment(column.getTextAlign(), currentCell);
      }
      currentColumn[0] = currentColumn[0] + 1;
      buildCell(value, currentCell, column);
    });
  }

  private Object transformToType(Object value, Column<T> column) {
    Object result = value;
    if (value instanceof String) {
      String stringValue = (String) value;
      String parsingFormatPattern =
          (String) ComponentUtil.getData(column, GridExporter.COLUMN_PARSING_FORMAT_PATTERN_DATA);
      try {
        if (parsingFormatPattern != null) {
          switch ((String) ComponentUtil.getData(column, GridExporter.COLUMN_TYPE_DATA)) {
            case GridExporter.COLUMN_TYPE_NUMBER:
              DecimalFormat decimalFormat = new DecimalFormat(parsingFormatPattern);
              decimalFormat.setParseBigDecimal(true);
              result = decimalFormat.parse(stringValue).doubleValue();
              break;
            case GridExporter.COLUMN_TYPE_DATE:
              result = new SimpleDateFormat(parsingFormatPattern).parse(stringValue);
              break;
          }
        }
      } catch (ParseException e) {
        throw new IllegalStateException(String
            .format("Problem parsing grid cell value with format: %s", parsingFormatPattern), e);
      }
    }
    return result;
  }

  protected void configureAlignment(ColumnTextAlign columnTextAlign, Cell currentCell) {
    switch (columnTextAlign) {
      case START:
        currentCell.getCellStyle().setAlignment(HorizontalAlignment.LEFT);
        break;
      case CENTER:
        currentCell.getCellStyle().setAlignment(HorizontalAlignment.CENTER);
        break;
      case END:
        currentCell.getCellStyle().setAlignment(HorizontalAlignment.RIGHT);
        break;
      default:
        currentCell.getCellStyle().setAlignment(HorizontalAlignment.LEFT);
    }
  }



  private void buildCell(Object value, Cell cell, Column<T> column) {
    String excelFormat = (String) ComponentUtil.getData(column, GridExporter.COLUMN_EXCEL_FORMAT_DATA);
    if (value == null) {
      PoiHelper.setBlank(cell);
    } else if (value instanceof Double) {
      applyNumericFormat(cell, (Double) value, excelFormat);
    } else if (value instanceof Date) {
      applyNumericFormat(cell, (Date) value, excelFormat);
    } else if (value instanceof LocalDate) {
      applyNumericFormat(cell, (LocalDate) value, excelFormat);
    } else {
      cell.setCellValue(value.toString());
    }
  }

  public void applyNumericFormat(Cell cell, Object value,
      String styleFormat) {
    DataFormat format = cell.getSheet().getWorkbook().createDataFormat();
    cell.getCellStyle().setDataFormat(format.getFormat(styleFormat));
    if (value instanceof Double) {
      cell.setCellValue((Double)value);
    } else if (value instanceof Date) {
      cell.setCellValue((Date)value);
    } else if (value instanceof LocalDate) {
      cell.setCellValue(Date.from(((LocalDate)value).atStartOfDay(ZoneId.systemDefault()).toInstant()));
    }
  }

  private Workbook getBaseTemplateWorkbook() {
    try {
      InputStream inp = this.getClass().getResourceAsStream(template);
      Workbook result = WorkbookFactory.create(inp);
      return result;
    } catch (Exception e) {
      throw new IllegalStateException("Problem creating workbook", e);
    }
  }

  private Cell findCellWithPlaceHolder(Sheet sheet, String placeholder) {
    for (Row row : sheet) {
      for (Cell cell : row) {
        if (PoiHelper.cellTypeEquals(cell, CellType.STRING)) {
          if (cell.getRichStringCellValue().getString().trim().equals(placeholder)) {
            return cell;
          }
        }
      }
    }
    return null;
  }

  private void fillHeaderOrFooter(Sheet sheet, Cell headersOrFootersCell,
      List<Pair<String, Column<T>>> headersOrFooters, boolean isHeader) {
    CellStyle style = headersOrFootersCell.getCellStyle();
    sheet.setActiveCell(headersOrFootersCell.getAddress());
    headersOrFooters.forEach(headerOrFooter -> {
      Cell cell =
          sheet.getRow(sheet.getActiveCell().getRow()).getCell(sheet.getActiveCell().getColumn());
      if (cell == null) {
        cell = sheet.getRow(sheet.getActiveCell().getRow())
            .createCell(sheet.getActiveCell().getColumn());
        CellStyle newCellStyle = cell.getSheet().getWorkbook().createCellStyle();
        newCellStyle.cloneStyleFrom(style);
        cell.setCellStyle(newCellStyle);
      }
      Object value = (isHeader?headerOrFooter.getLeft():transformToType(headerOrFooter.getLeft(), headerOrFooter.getRight()));
      buildCell(value, cell, headerOrFooter.getRight());
      configureAlignment(headerOrFooter.getRight().getTextAlign(), cell);
      sheet.setActiveCell(
          new CellAddress(sheet.getActiveCell().getRow(), sheet.getActiveCell().getColumn() + 1));
    });
  }
}
