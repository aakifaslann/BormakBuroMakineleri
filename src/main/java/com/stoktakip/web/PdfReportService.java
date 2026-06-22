package com.stoktakip.web;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.awt.Color;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class PdfReportService {

    public void generateReport(String filePath, int year, Integer employeeId, 
                               boolean includeOverview, boolean includeHandovers, boolean includeMachines,
                               Connection conn) throws Exception {
        
        try (PDDocument document = new PDDocument()) {
            int currentPage = 1;
            
            // Calculate total pages
            int totalPages = 0;
            if (includeOverview) totalPages++;
            if (includeHandovers) totalPages++;
            if (includeMachines) totalPages++;
            
            if (totalPages == 0) {
                includeOverview = true;
                totalPages = 1;
            }

            if (includeOverview) {
                PDPage page1 = new PDPage(PDRectangle.A4);
                document.addPage(page1);
                drawOverviewPage(document, page1, conn, currentPage++, totalPages);
            }

            if (includeHandovers) {
                PDPage page2 = new PDPage(PDRectangle.A4);
                document.addPage(page2);
                drawHandoversPage(document, page2, conn, employeeId, currentPage++, totalPages);
            }

            if (includeMachines) {
                PDPage page3 = new PDPage(PDRectangle.A4);
                document.addPage(page3);
                drawMachinesPage(document, page3, conn, year, currentPage++, totalPages);
            }

            document.save(filePath);
        }
    }

    private void drawOverviewPage(PDDocument doc, PDPage page, Connection conn, int pageNum, int totalPages) throws Exception {
        try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
            Color primary = new Color(124, 58, 237); // Purple
            Color navy = new Color(15, 23, 42); // Slate 900
            Color textDark = new Color(51, 65, 85); // Slate 700
            Color lightGray = new Color(248, 250, 252); // Background gray
            Color lineBlue = new Color(37, 99, 235); // Blue

            // 1. Header Banner
            drawRect(stream, 50, 730, 495, 60, navy);
            drawText(stream, PDType1Font.HELVETICA_BOLD, 14, Color.WHITE, 65, 765, "BORMAK BURO MAKINELERI - STOK & SAYAÇ PORTALI");
            drawText(stream, PDType1Font.HELVETICA_BOLD, 10, new Color(224, 224, 224), 65, 745, "Genel Bakis ve Depo Durum Raporu");
            
            String dateStr = "Tarih: " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
            drawTextRightAligned(stream, PDType1Font.HELVETICA_BOLD, 10, new Color(250, 204, 21), 530, 755, dateStr);

            // 2. Statistics Grid (Draw 4 summary blocks)
            drawText(stream, PDType1Font.HELVETICA_BOLD, 12, navy, 50, 695, "1. Sistem Genel Ozet Gostergeleri");
            drawLine(stream, 50, 688, 545, 688, 1f, primary);

            int totalStock = 0;
            int totalEmployees = 0;
            int totalCustomers = 0;
            int criticalMeters = 0;

            // Fetch Overview Data
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT SUM(warehouse_quantity) FROM products")) {
                if (rs.next()) totalStock = rs.getInt(1);
            }
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM employees")) {
                if (rs.next()) totalEmployees = rs.getInt(1);
            }
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM customers")) {
                if (rs.next()) totalCustomers = rs.getInt(1);
            }
            String warningQuery = "SELECT COUNT(*) FROM customer_machines cm " +
                    "LEFT JOIN (SELECT machine_id, MAX(reading_date) AS last_date FROM meter_readings GROUP BY machine_id) mr " +
                    "ON cm.id = mr.machine_id " +
                    "JOIN customers c ON cm.customer_id = c.id " +
                    "WHERE c.business_model = 'KOPYA_BASI' AND (mr.last_date IS NULL OR mr.last_date < NOW() - INTERVAL '30' DAY)";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(warningQuery)) {
                if (rs.next()) criticalMeters = rs.getInt(1);
            }

            // Draw KPI Boxes
            float boxY = 620;
            drawRect(stream, 50, boxY, 110, 50, lightGray);
            drawText(stream, PDType1Font.HELVETICA, 8, textDark, 55, boxY + 35, "Toplam Depo Stok");
            drawText(stream, PDType1Font.HELVETICA_BOLD, 14, primary, 55, boxY + 12, totalStock + " Adet");

            drawRect(stream, 175, boxY, 110, 50, lightGray);
            drawText(stream, PDType1Font.HELVETICA, 8, textDark, 180, boxY + 35, "Toplam Personel");
            drawText(stream, PDType1Font.HELVETICA_BOLD, 14, primary, 180, boxY + 12, totalEmployees + " Kisi");

            drawRect(stream, 300, boxY, 110, 50, lightGray);
            drawText(stream, PDType1Font.HELVETICA, 8, textDark, 305, boxY + 35, "Toplam Musteri");
            drawText(stream, PDType1Font.HELVETICA_BOLD, 14, primary, 305, boxY + 12, totalCustomers + " Firma");

            drawRect(stream, 425, boxY, 120, 50, lightGray);
            drawText(stream, PDType1Font.HELVETICA, 8, textDark, 430, boxY + 35, "Kritik Sayac Durumu");
            drawText(stream, PDType1Font.HELVETICA_BOLD, 14, Color.RED, 430, boxY + 12, criticalMeters + " Cihaz");

            // 3. Warehouse Products list
            float y = 540;
            drawText(stream, PDType1Font.HELVETICA_BOLD, 12, navy, 50, y, "2. Depo Urun Katalogu Listesi");
            drawLine(stream, 50, y - 7, 545, y - 7, 1f, primary);

            y -= 25;
            // Draw Table Header
            drawRect(stream, 50, y - 5, 495, 22, lightGray);
            drawText(stream, PDType1Font.HELVETICA_BOLD, 9, navy, 60, y, "Stok Kodu");
            drawText(stream, PDType1Font.HELVETICA_BOLD, 9, navy, 180, y, "Urun Adi");
            drawText(stream, PDType1Font.HELVETICA_BOLD, 9, navy, 380, y, "Tedarikci");
            drawTextRightAligned(stream, PDType1Font.HELVETICA_BOLD, 9, navy, 535, y, "Depo Miktar");

            drawLine(stream, 50, y - 6, 545, y - 6, 0.5f, Color.LIGHT_GRAY);

            // Fetch products
            String prodQuery = "SELECT stock_code, name, supplier_name, warehouse_quantity FROM products ORDER BY warehouse_quantity DESC LIMIT 18";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(prodQuery)) {
                while (rs.next()) {
                    y -= 20;
                    if (y < 70) break; // Safeguard

                    String code = cleanText(rs.getString("stock_code"));
                    String name = cleanText(rs.getString("name"));
                    String supplier = cleanText(rs.getString("supplier_name"));
                    int qty = rs.getInt("warehouse_quantity");

                    drawText(stream, PDType1Font.HELVETICA, 8.5f, textDark, 60, y, code);
                    drawText(stream, PDType1Font.HELVETICA, 8.5f, textDark, 180, y, name.length() > 35 ? name.substring(0, 32) + "..." : name);
                    drawText(stream, PDType1Font.HELVETICA, 8.5f, textDark, 380, y, supplier != null ? (supplier.length() > 22 ? supplier.substring(0, 19) + "..." : supplier) : "-");
                    drawTextRightAligned(stream, PDType1Font.HELVETICA_BOLD, 8.5f, textDark, 535, y, String.valueOf(qty));
                    drawLine(stream, 50, y - 5, 545, y - 5, 0.5f, Color.LIGHT_GRAY);
                }
            }

            drawFooter(stream, pageNum, totalPages);
        }
    }

    private void drawHandoversPage(PDDocument doc, PDPage page, Connection conn, Integer filterEmployeeId, int pageNum, int totalPages) throws Exception {
        try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
            Color primary = new Color(124, 58, 237); // Purple
            Color navy = new Color(15, 23, 42); // Slate 900
            Color textDark = new Color(51, 65, 85); // Slate 700
            Color lightGray = new Color(248, 250, 252);

            // Small Header
            drawText(stream, PDType1Font.HELVETICA_BOLD, 9, new Color(148, 163, 184), 50, 780, "BORMAK PORTAL - PERSONEL ZIMMET DETAYLARI");
            drawLine(stream, 50, 770, 545, 770, 0.5f, Color.LIGHT_GRAY);

            drawText(stream, PDType1Font.HELVETICA_BOLD, 12, navy, 50, 745, "3. Personel Zimmetli Stok Raporu");
            drawLine(stream, 50, 737, 545, 737, 1f, primary);

            float y = 715;
            // Draw Table Header
            drawRect(stream, 50, y - 5, 495, 22, lightGray);
            drawText(stream, PDType1Font.HELVETICA_BOLD, 9, navy, 60, y, "Personel");
            drawText(stream, PDType1Font.HELVETICA_BOLD, 9, navy, 180, y, "Stok Kodu");
            drawText(stream, PDType1Font.HELVETICA_BOLD, 9, navy, 280, y, "Urun Adi");
            drawTextRightAligned(stream, PDType1Font.HELVETICA_BOLD, 9, navy, 535, y, "Zimmet Adet");

            drawLine(stream, 50, y - 6, 545, y - 6, 0.5f, Color.LIGHT_GRAY);

            String query = "SELECT e.first_name, e.last_name, e.employee_code, p.stock_code, p.name AS prod_name, h.quantity " +
                    "FROM employee_handovers h " +
                    "JOIN employees e ON h.employee_id = e.id " +
                    "JOIN products p ON h.product_id = p.id ";
            
            if (filterEmployeeId != null && filterEmployeeId > 0) {
                query += "WHERE e.id = ? ";
            }
            query += "ORDER BY e.first_name ASC, h.quantity DESC LIMIT 28";

            try (PreparedStatement ps = conn.prepareStatement(query)) {
                if (filterEmployeeId != null && filterEmployeeId > 0) {
                    ps.setInt(1, filterEmployeeId);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        y -= 20;
                        if (y < 70) break;

                        String empName = cleanText(rs.getString("first_name") + " " + rs.getString("last_name") + " (" + rs.getString("employee_code") + ")");
                        String stockCode = cleanText(rs.getString("stock_code"));
                        String prodName = cleanText(rs.getString("prod_name"));
                        int qty = rs.getInt("quantity");

                        drawText(stream, PDType1Font.HELVETICA, 8.5f, textDark, 60, y, empName.length() > 24 ? empName.substring(0, 22) + ".." : empName);
                        drawText(stream, PDType1Font.HELVETICA, 8.5f, textDark, 180, y, stockCode);
                        drawText(stream, PDType1Font.HELVETICA, 8.5f, textDark, 280, y, prodName.length() > 36 ? prodName.substring(0, 33) + "..." : prodName);
                        drawTextRightAligned(stream, PDType1Font.HELVETICA_BOLD, 8.5f, textDark, 535, y, String.valueOf(qty));
                        drawLine(stream, 50, y - 5, 545, y - 5, 0.5f, Color.LIGHT_GRAY);
                    }
                }
            }

            drawFooter(stream, pageNum, totalPages);
        }
    }

    private void drawMachinesPage(PDDocument doc, PDPage page, Connection conn, int year, int pageNum, int totalPages) throws Exception {
        try (PDPageContentStream stream = new PDPageContentStream(doc, page)) {
            Color primary = new Color(124, 58, 237); // Purple
            Color navy = new Color(15, 23, 42); // Slate 900
            Color textDark = new Color(51, 65, 85); // Slate 700
            Color lightGray = new Color(248, 250, 252);

            // Small Header
            drawText(stream, PDType1Font.HELVETICA_BOLD, 9, new Color(148, 163, 184), 50, 780, "BORMAK PORTAL - MUSTERI CIHAZ DURUMLARI");
            drawLine(stream, 50, 770, 545, 770, 0.5f, Color.LIGHT_GRAY);

            drawText(stream, PDType1Font.HELVETICA_BOLD, 12, navy, 50, 745, "4. Musteri Makine Sayaç & Hasilat Durumu");
            drawLine(stream, 50, 737, 545, 737, 1f, primary);

            float y = 715;
            // Draw Table Header
            drawRect(stream, 50, y - 5, 495, 22, lightGray);
            drawText(stream, PDType1Font.HELVETICA_BOLD, 8.5f, navy, 60, y, "Musteri / Firma");
            drawText(stream, PDType1Font.HELVETICA_BOLD, 8.5f, navy, 180, y, "Cihaz / Model");
            drawText(stream, PDType1Font.HELVETICA_BOLD, 8.5f, navy, 280, y, "Seri No");
            drawTextRightAligned(stream, PDType1Font.HELVETICA_BOLD, 8.5f, navy, 380, y, "Baslangic Say.");
            drawTextRightAligned(stream, PDType1Font.HELVETICA_BOLD, 8.5f, navy, 460, y, "Guncel Say.");
            drawTextRightAligned(stream, PDType1Font.HELVETICA_BOLD, 8.5f, navy, 535, y, "Toplam Kopya");

            drawLine(stream, 50, y - 6, 545, y - 6, 0.5f, Color.LIGHT_GRAY);

            String query = "SELECT c.company_name, m.machine_name, m.serial_number, m.initial_meter, m.current_meter " +
                    "FROM customer_machines m JOIN customers c ON m.customer_id = c.id " +
                    "ORDER BY c.company_name ASC LIMIT 16";
            
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    y -= 20;
                    if (y < 280) break;

                    String comp = cleanText(rs.getString("company_name"));
                    String mach = cleanText(rs.getString("machine_name"));
                    String serial = cleanText(rs.getString("serial_number"));
                    int init = rs.getInt("initial_meter");
                    int cur = rs.getInt("current_meter");
                    int diff = cur - init;

                    drawText(stream, PDType1Font.HELVETICA, 8, textDark, 60, y, comp.length() > 22 ? comp.substring(0, 20) + ".." : comp);
                    drawText(stream, PDType1Font.HELVETICA, 8, textDark, 180, y, mach.length() > 18 ? mach.substring(0, 16) + ".." : mach);
                    drawText(stream, PDType1Font.HELVETICA, 8, textDark, 280, y, serial);
                    drawTextRightAligned(stream, PDType1Font.HELVETICA, 8, textDark, 380, y, String.valueOf(init));
                    drawTextRightAligned(stream, PDType1Font.HELVETICA, 8, textDark, 460, y, String.valueOf(cur));
                    drawTextRightAligned(stream, PDType1Font.HELVETICA_BOLD, 8, diff >= 0 ? new Color(16, 185, 129) : Color.RED, 535, y, String.valueOf(diff));
                    drawLine(stream, 50, y - 5, 545, y - 5, 0.5f, Color.LIGHT_GRAY);
                }
            }

            // Draw Yearly Matrix preview at bottom
            y -= 40;
            drawText(stream, PDType1Font.HELVETICA_BOLD, 10, navy, 50, y, "5. Yillik Hasilat ve Kopya Sayisi Özetleri (" + year + ")");
            drawLine(stream, 50, y - 6, 545, y - 6, 0.5f, primary);

            y -= 25;
            drawRect(stream, 50, y - 5, 495, 20, lightGray);
            drawText(stream, PDType1Font.HELVETICA_BOLD, 8, navy, 60, y, "Musteri / Firma");
            drawTextRightAligned(stream, PDType1Font.HELVETICA_BOLD, 8, navy, 300, y, "Sorumlu Personel");
            drawTextRightAligned(stream, PDType1Font.HELVETICA_BOLD, 8, navy, 410, y, "Yillik Toplam Baskı");
            drawTextRightAligned(stream, PDType1Font.HELVETICA_BOLD, 8, navy, 535, y, "Yillik Toplam Ciro");
            drawLine(stream, 50, y - 6, 545, y - 6, 0.5f, Color.LIGHT_GRAY);

            String yearlyQuery = "SELECT c.company_name, e.first_name, e.last_name, " +
                    "SUM(mr.difference) AS yearly_copies, " +
                    "SUM(mr.total_amount) AS yearly_revenue " +
                    "FROM meter_readings mr " +
                    "JOIN customers c ON mr.customer_id = c.id " +
                    "LEFT JOIN employees e ON c.responsible_employee_id = e.id " +
                    "WHERE EXTRACT(YEAR FROM mr.reading_date) = ? " +
                    "GROUP BY c.id, c.company_name, e.first_name, e.last_name " +
                    "ORDER BY yearly_revenue DESC LIMIT 5";

            try (PreparedStatement ps = conn.prepareStatement(yearlyQuery)) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        y -= 20;
                        if (y < 70) break;

                        String compName = cleanText(rs.getString("company_name"));
                        String empName = cleanText(rs.getString("first_name") != null ? rs.getString("first_name") + " " + rs.getString("last_name") : "Sorumlu Yok");
                        int copies = rs.getInt("yearly_copies");
                        double revenue = rs.getDouble("yearly_revenue");

                        drawText(stream, PDType1Font.HELVETICA, 8, textDark, 60, y, compName.length() > 28 ? compName.substring(0, 25) + "..." : compName);
                        drawTextRightAligned(stream, PDType1Font.HELVETICA, 8, textDark, 300, y, empName);
                        drawTextRightAligned(stream, PDType1Font.HELVETICA_BOLD, 8, textDark, 410, y, String.format("%,d Adet", copies));
                        drawTextRightAligned(stream, PDType1Font.HELVETICA_BOLD, 8, new Color(37, 99, 235), 535, y, String.format("%,.2f TL", revenue));
                        drawLine(stream, 50, y - 5, 545, y - 5, 0.5f, Color.LIGHT_GRAY);
                    }
                }
            }

            drawFooter(stream, pageNum, totalPages);
        }
    }

    private void drawFooter(PDPageContentStream stream, int pageNum, int totalPages) throws IOException {
        Color textMuted = new Color(148, 163, 184);
        drawLine(stream, 50, 45, 545, 45, 0.5f, Color.LIGHT_GRAY);
        drawText(stream, PDType1Font.HELVETICA, 8, textMuted, 50, 32, "Bormak Buro Makineleri Portal Sistemi");
        drawTextRightAligned(stream, PDType1Font.HELVETICA, 8, textMuted, 545, 32, "Sayfa " + pageNum + " / " + totalPages);
    }

    private void drawText(PDPageContentStream stream, PDFont font, float size, Color color, float x, float y, String text) throws IOException {
        stream.beginText();
        stream.setFont(font, size);
        stream.setNonStrokingColor(color);
        stream.newLineAtOffset(x, y);
        stream.showText(text);
        stream.endText();
    }

    private void drawTextRightAligned(PDPageContentStream stream, PDFont font, float size, Color color, float xRight, float y, String text) throws IOException {
        float width = font.getStringWidth(text) / 1000 * size;
        drawText(stream, font, size, color, xRight - width, y, text);
    }

    private void drawLine(PDPageContentStream stream, float x1, float y1, float x2, float y2, float width, Color color) throws IOException {
        stream.setStrokingColor(color);
        stream.setLineWidth(width);
        stream.moveTo(x1, y1);
        stream.lineTo(x2, y2);
        stream.stroke();
    }

    private void drawRect(PDPageContentStream stream, float x, float y, float width, float height, Color color) throws IOException {
        stream.setNonStrokingColor(color);
        stream.addRect(x, y, width, height);
        stream.fill();
    }

    private String cleanText(String text) {
        if (text == null) return "";
        return text.replace("ı", "i")
                   .replace("İ", "I")
                   .replace("ş", "s")
                   .replace("Ş", "S")
                   .replace("ğ", "g")
                   .replace("Ğ", "G")
                   .replace("ü", "u")
                   .replace("Ü", "U")
                   .replace("ö", "o")
                   .replace("Ö", "O")
                   .replace("ç", "c")
                   .replace("Ç", "C");
    }
}
