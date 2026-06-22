package com.stoktakip.web;

import static spark.Spark.*;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.InputStream;
import java.net.URL;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class WebServer {
    private static int PORT = 3000;
    private static HikariDataSource dataSource;
    private static final Gson gson = new GsonBuilder().setDateFormat("yyyy-MM-dd HH:mm:ss").create();

    public static void main(String[] args) {
        // Configure port dynamically
        String portEnv = System.getenv("PORT");
        if (portEnv != null && !portEnv.isEmpty()) {
            try {
                PORT = Integer.parseInt(portEnv);
            } catch (NumberFormatException e) {
                // Keep default 3000
            }
        }
        port(PORT);

        // Configure static files
        staticFiles.location("/public");

        // Initialize Database Connection Pool
        initializeDataSource();

        // Enable CORS
        before((request, response) -> {
            response.header("Access-Control-Allow-Origin", "*");
            response.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            response.header("Access-Control-Allow-Headers", "Content-Type, Authorization, Accept");
        });

        options("/*", (request, response) -> {
            String accessControlRequestHeaders = request.headers("Access-Control-Request-Headers");
            if (accessControlRequestHeaders != null) {
                response.header("Access-Control-Allow-Headers", accessControlRequestHeaders);
            }
            String accessControlRequestMethod = request.headers("Access-Control-Request-Method");
            if (accessControlRequestMethod != null) {
                response.header("Access-Control-Allow-Methods", accessControlRequestMethod);
            }
            return "OK";
        });

        // ----------------------------------------------------
        // API ROUTES
        // ----------------------------------------------------

        // Authenticate User
        post("/api/auth/login", (req, res) -> {
            res.type("application/json");
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            String username = body.get("username").getAsString();
            String password = body.get("password").getAsString();

            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT id, username, full_name, role FROM users WHERE username = ? AND password = ?")) {
                ps.setString(1, username);
                ps.setString(2, password);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        JsonObject user = new JsonObject();
                        user.addProperty("id", rs.getInt("id"));
                        user.addProperty("username", rs.getString("username"));
                        user.addProperty("fullName", rs.getString("full_name"));
                        user.addProperty("role", rs.getString("role"));
                        return successResponse(user);
                    }
                }
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
            return errorResponse(res, 401, "Hatalı kullanıcı adı veya şifre!");
        });

        // Dashboard Stats
        get("/api/dashboard-summary", (req, res) -> {
            res.type("application/json");
            JsonObject summary = new JsonObject();
            try (Connection conn = getConnection()) {
                // Total products count
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT SUM(warehouse_quantity) FROM products")) {
                    summary.addProperty("totalWarehouseStock", rs.next() ? rs.getInt(1) : 0);
                }
                // Total employees count
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM employees")) {
                    summary.addProperty("totalEmployees", rs.next() ? rs.getInt(1) : 0);
                }
                // Total customers count
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM customers")) {
                    summary.addProperty("totalCustomers", rs.next() ? rs.getInt(1) : 0);
                }
                // Warnings count: customer machines with meter readings older than 30 days or never read
                String warningQuery = "SELECT COUNT(*) FROM customer_machines cm " +
                        "LEFT JOIN (SELECT machine_id, MAX(reading_date) AS last_date FROM meter_readings GROUP BY machine_id) mr " +
                        "ON cm.id = mr.machine_id " +
                        "JOIN customers c ON cm.customer_id = c.id " +
                        "WHERE c.business_model = 'KOPYA_BASI' AND (mr.last_date IS NULL OR mr.last_date < NOW() - INTERVAL '30' DAY)";
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(warningQuery)) {
                    summary.addProperty("criticalMeterCount", rs.next() ? rs.getInt(1) : 0);
                }
                return successResponse(summary);
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        // Fetch TCMB Rates
        get("/api/currency/rates", (req, res) -> {
            res.type("application/json");
            try {
                URL url = new URL("https://www.tcmb.gov.tr/kurlar/today.xml");
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                DocumentBuilder db = dbf.newDocumentBuilder();
                
                // Allow network operations
                Document doc;
                try (InputStream is = url.openStream()) {
                    doc = db.parse(is);
                }
                
                doc.getDocumentElement().normalize();
                NodeList list = doc.getElementsByTagName("Currency");
                
                double usd = 0.0;
                double eur = 0.0;
                
                for (int i = 0; i < list.getLength(); i++) {
                    Element element = (Element) list.item(i);
                    String code = element.getAttribute("CurrencyCode");
                    if ("USD".equals(code)) {
                        usd = Double.parseDouble(element.getElementsByTagName("ForexBuying").item(0).getTextContent());
                    } else if ("EUR".equals(code)) {
                        eur = Double.parseDouble(element.getElementsByTagName("ForexBuying").item(0).getTextContent());
                    }
                }
                JsonObject rates = new JsonObject();
                rates.addProperty("usd", usd);
                rates.addProperty("eur", eur);
                rates.addProperty("loaded", true);
                return successResponse(rates);
            } catch (Exception e) {
                JsonObject err = new JsonObject();
                err.addProperty("loaded", false);
                err.addProperty("error", e.getMessage());
                return successResponse(err);
            }
        });

        // Products Endpoint
        get("/api/products", (req, res) -> {
            res.type("application/json");
            JsonArray arr = new JsonArray();
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id, stock_code, name, supplier_name, warehouse_quantity FROM products ORDER BY name ASC")) {
                while (rs.next()) {
                    JsonObject p = new JsonObject();
                    p.addProperty("id", rs.getInt("id"));
                    p.addProperty("stockCode", rs.getString("stock_code"));
                    p.addProperty("name", rs.getString("name"));
                    p.addProperty("supplierName", rs.getString("supplier_name"));
                    p.addProperty("warehouseQuantity", rs.getInt("warehouse_quantity"));
                    arr.add(p);
                }
                return successResponse(arr);
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        post("/api/products", (req, res) -> {
            res.type("application/json");
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            String stockCode = body.get("stockCode").getAsString();
            String name = body.get("name").getAsString();
            String supplierName = body.get("supplierName") != null ? body.get("supplierName").getAsString() : "";
            int initialQty = body.get("initialQty") != null ? body.get("initialQty").getAsInt() : 0;

            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                try {
                    // Check duplicate stock code
                    try (PreparedStatement check = conn.prepareStatement("SELECT COUNT(*) FROM products WHERE stock_code = ?")) {
                        check.setString(1, stockCode);
                        try (ResultSet rs = check.executeQuery()) {
                            if (rs.next() && rs.getInt(1) > 0) {
                                return errorResponse(res, 400, "Bu stok koduna sahip başka bir ürün zaten kayıtlı!");
                            }
                        }
                    }

                    int productId;
                    String insertProd = "INSERT INTO products (stock_code, name, supplier_name, warehouse_quantity) VALUES (?, ?, ?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(insertProd, Statement.RETURN_GENERATED_KEYS)) {
                        ps.setString(1, stockCode);
                        ps.setString(2, name);
                        ps.setString(3, supplierName);
                        ps.setInt(4, initialQty);
                        ps.executeUpdate();
                        try (ResultSet generatedKeys = ps.getGeneratedKeys()) {
                            if (generatedKeys.next()) {
                                productId = generatedKeys.getInt(1);
                            } else {
                                throw new SQLException("Creating product failed, no ID obtained.");
                            }
                        }
                    }

                    // Log stock movement if initialQty > 0
                    if (initialQty > 0) {
                        String insertMov = "INSERT INTO stock_movements (transaction_type, product_id, quantity, description) VALUES ('WAREHOUSE_ENTRY', ?, ?, ?)";
                        try (PreparedStatement ps = conn.prepareStatement(insertMov)) {
                            ps.setInt(1, productId);
                            ps.setInt(2, initialQty);
                            ps.setString(3, "Yeni ürün tanımıyla otomatik depoya giriş");
                            ps.executeUpdate();
                        }
                    }

                    conn.commit();
                    return successResponse("Ürün başarıyla kaydedildi.");
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                }
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        put("/api/products/:id", (req, res) -> {
            res.type("application/json");
            int id = Integer.parseInt(req.params(":id"));
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            String stockCode = body.get("stockCode").getAsString();
            String name = body.get("name").getAsString();
            String supplierName = body.get("supplierName") != null ? body.get("supplierName").getAsString() : "";

            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("UPDATE products SET stock_code = ?, name = ?, supplier_name = ? WHERE id = ?")) {
                ps.setString(1, stockCode);
                ps.setString(2, name);
                ps.setString(3, supplierName);
                ps.setInt(4, id);
                if (ps.executeUpdate() > 0) {
                    return successResponse("Ürün güncellendi.");
                }
                return errorResponse(res, 404, "Ürün bulunamadı.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        delete("/api/products/:id", (req, res) -> {
            res.type("application/json");
            int id = Integer.parseInt(req.params(":id"));
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM products WHERE id = ?")) {
                ps.setInt(1, id);
                if (ps.executeUpdate() > 0) {
                    return successResponse("Ürün silindi.");
                }
                return errorResponse(res, 404, "Ürün bulunamadı.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        // Warehouse Entry
        post("/api/products/entry", (req, res) -> {
            res.type("application/json");
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            int productId = body.get("productId").getAsInt();
            int qty = body.get("qty").getAsInt();

            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                try {
                    // Update product warehouse quantity
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE products SET warehouse_quantity = warehouse_quantity + ? WHERE id = ?")) {
                        ps.setInt(1, qty);
                        ps.setInt(2, productId);
                        ps.executeUpdate();
                    }
                    // Log movement
                    try (PreparedStatement ps = conn.prepareStatement("INSERT INTO stock_movements (transaction_type, product_id, quantity, description) VALUES ('WAREHOUSE_ENTRY', ?, ?, 'Depoya el ile ürün girişi')")) {
                        ps.setInt(1, productId);
                        ps.setInt(2, qty);
                        ps.executeUpdate();
                    }
                    conn.commit();
                    return successResponse("Depo girişi yapıldı.");
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                }
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        // Employees Endpoint
        get("/api/employees", (req, res) -> {
            res.type("application/json");
            JsonArray arr = new JsonArray();
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id, employee_code, first_name, last_name FROM employees ORDER BY first_name, last_name ASC")) {
                while (rs.next()) {
                    JsonObject e = new JsonObject();
                    e.addProperty("id", rs.getInt("id"));
                    e.addProperty("employeeCode", rs.getString("employee_code"));
                    e.addProperty("firstName", rs.getString("first_name"));
                    e.addProperty("lastName", rs.getString("last_name"));
                    e.addProperty("fullName", rs.getString("first_name") + " " + rs.getString("last_name"));
                    arr.add(e);
                }
                return successResponse(arr);
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        post("/api/employees", (req, res) -> {
            res.type("application/json");
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            String code = body.get("employeeCode").getAsString();
            String firstName = body.get("firstName").getAsString();
            String lastName = body.get("lastName").getAsString();

            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("INSERT INTO employees (employee_code, first_name, last_name) VALUES (?, ?, ?)")) {
                ps.setString(1, code);
                ps.setString(2, firstName);
                ps.setString(3, lastName);
                ps.executeUpdate();
                return successResponse("Personel kaydedildi.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        put("/api/employees/:id", (req, res) -> {
            res.type("application/json");
            int id = Integer.parseInt(req.params(":id"));
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            String code = body.get("employeeCode").getAsString();
            String firstName = body.get("firstName").getAsString();
            String lastName = body.get("lastName").getAsString();

            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("UPDATE employees SET employee_code = ?, first_name = ?, last_name = ? WHERE id = ?")) {
                ps.setString(1, code);
                ps.setString(2, firstName);
                ps.setString(3, lastName);
                ps.setInt(4, id);
                if (ps.executeUpdate() > 0) {
                    return successResponse("Personel güncellendi.");
                }
                return errorResponse(res, 404, "Personel bulunamadı.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        delete("/api/employees/:id", (req, res) -> {
            res.type("application/json");
            int id = Integer.parseInt(req.params(":id"));
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM employees WHERE id = ?")) {
                ps.setInt(1, id);
                if (ps.executeUpdate() > 0) {
                    return successResponse("Personel silindi.");
                }
                return errorResponse(res, 404, "Personel bulunamadı.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        // Employee Handovers (Zimmets)
        get("/api/employees/:id/handovers", (req, res) -> {
            res.type("application/json");
            int empId = Integer.parseInt(req.params(":id"));
            JsonArray arr = new JsonArray();
            String query = "SELECT h.product_id, h.quantity, p.stock_code, p.name FROM employee_handovers h JOIN products p ON h.product_id = p.id WHERE h.employee_id = ?";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setInt(1, empId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject h = new JsonObject();
                        h.addProperty("productId", rs.getInt("product_id"));
                        h.addProperty("quantity", rs.getInt("quantity"));
                        h.addProperty("stockCode", rs.getString("stock_code"));
                        h.addProperty("productName", rs.getString("name"));
                        arr.add(h);
                    }
                }
                return successResponse(arr);
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        // Transfer from Warehouse to Employee (Zimmete Aktar)
        post("/api/employees/transfer", (req, res) -> {
            res.type("application/json");
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            int productId = body.get("productId").getAsInt();
            int employeeId = body.get("employeeId").getAsInt();
            int qty = body.get("qty").getAsInt();
            String desc = body.get("description") != null ? body.get("description").getAsString() : "";

            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                try {
                    // Check stock in warehouse
                    int currentStock = 0;
                    try (PreparedStatement check = conn.prepareStatement("SELECT warehouse_quantity FROM products WHERE id = ?")) {
                        check.setInt(1, productId);
                        try (ResultSet rs = check.executeQuery()) {
                            if (rs.next()) {
                                currentStock = rs.getInt(1);
                            }
                        }
                    }

                    if (qty > currentStock) {
                        return errorResponse(res, 400, "Depoda yeterli ürün stoğu bulunmamaktadır! Mevcut Depo Stoğu: " + currentStock);
                    }

                    // Decrement warehouse quantity
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE products SET warehouse_quantity = warehouse_quantity - ? WHERE id = ?")) {
                        ps.setInt(1, qty);
                        ps.setInt(2, productId);
                        ps.executeUpdate();
                    }

                    // Increment employee handover quantity
                    boolean exists = false;
                    try (PreparedStatement check = conn.prepareStatement("SELECT COUNT(*) FROM employee_handovers WHERE employee_id = ? AND product_id = ?")) {
                        check.setInt(1, employeeId);
                        check.setInt(2, productId);
                        try (ResultSet rs = check.executeQuery()) {
                            if (rs.next() && rs.getInt(1) > 0) exists = true;
                        }
                    }

                    if (exists) {
                        try (PreparedStatement ps = conn.prepareStatement("UPDATE employee_handovers SET quantity = quantity + ? WHERE employee_id = ? AND product_id = ?")) {
                            ps.setInt(1, qty);
                            ps.setInt(2, employeeId);
                            ps.setInt(3, productId);
                            ps.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO employee_handovers (employee_id, product_id, quantity) VALUES (?, ?, ?)")) {
                            ps.setInt(1, employeeId);
                            ps.setInt(2, productId);
                            ps.setInt(3, qty);
                            ps.executeUpdate();
                        }
                    }

                    // Log stock movement
                    try (PreparedStatement ps = conn.prepareStatement("INSERT INTO stock_movements (transaction_type, product_id, employee_id, quantity, description) " +
                            "VALUES ('WAREHOUSE_TO_EMPLOYEE', ?, ?, ?, ?)")) {
                        ps.setInt(1, productId);
                        ps.setInt(2, employeeId);
                        ps.setInt(3, qty);
                        ps.setString(4, desc.isEmpty() ? "Depodan zimmete aktarım" : desc);
                        ps.executeUpdate();
                    }

                    conn.commit();
                    return successResponse("Zimmet aktarımı başarıyla tamamlandı.");
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                }
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        // Customers Endpoint
        get("/api/customers", (req, res) -> {
            res.type("application/json");
            JsonArray arr = new JsonArray();
            String query = "SELECT c.id, c.customer_code, c.company_name, c.unit_price, c.business_model, c.responsible_employee_id, " +
                    "e.first_name, e.last_name, " +
                    "(SELECT MAX(reading_date) FROM meter_readings WHERE customer_id = c.id) AS last_reading_date " +
                    "FROM customers c LEFT JOIN employees e ON c.responsible_employee_id = e.id " +
                    "ORDER BY c.company_name ASC";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(query);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject c = new JsonObject();
                    c.addProperty("id", rs.getInt("id"));
                    c.addProperty("customerCode", rs.getString("customer_code"));
                    c.addProperty("companyName", rs.getString("company_name"));
                    c.addProperty("unitPrice", rs.getDouble("unit_price"));
                    c.addProperty("businessModel", rs.getString("business_model"));
                    
                    int responsibleId = rs.getInt("responsible_employee_id");
                    if (!rs.wasNull()) {
                        c.addProperty("responsibleId", responsibleId);
                        c.addProperty("responsibleName", rs.getString("first_name") + " " + rs.getString("last_name"));
                    } else {
                        c.addProperty("responsibleName", "Sorumlu Yok");
                    }
                    
                    Timestamp ts = rs.getTimestamp("last_reading_date");
                    if (ts != null) {
                        c.addProperty("lastReadingDate", ts.toString());
                        long diff = System.currentTimeMillis() - ts.getTime();
                        long days = diff / (1000 * 60 * 60 * 24);
                        if (days > 30) {
                            c.addProperty("meterStatus", "KRİTİK - " + days + " Gün Önce");
                        } else {
                            c.addProperty("meterStatus", days + " Gün Önce Okundu");
                        }
                    } else {
                        c.addProperty("meterStatus", "Sayaç Okuması Yok");
                    }
                    arr.add(c);
                }
                return successResponse(arr);
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        post("/api/customers", (req, res) -> {
            res.type("application/json");
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            String code = body.get("customerCode").getAsString();
            String companyName = body.get("companyName").getAsString();
            double unitPrice = body.get("unitPrice").getAsDouble();
            String businessModel = body.get("businessModel").getAsString();
            
            Integer responsibleId = null;
            if (body.get("responsibleId") != null && !body.get("responsibleId").isJsonNull()) {
                responsibleId = body.get("responsibleId").getAsInt();
            }

            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                try {
                    int customerId = 0;
                    String query = "INSERT INTO customers (customer_code, company_name, unit_price, business_model, responsible_employee_id) VALUES (?, ?, ?, ?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(query, Statement.RETURN_GENERATED_KEYS)) {
                        ps.setString(1, code);
                        ps.setString(2, companyName);
                        ps.setDouble(3, unitPrice);
                        ps.setString(4, businessModel);
                        if (responsibleId != null) ps.setInt(5, responsibleId); else ps.setNull(5, Types.INTEGER);
                        ps.executeUpdate();
                        try (ResultSet rs = ps.getGeneratedKeys()) {
                            if (rs.next()) customerId = rs.getInt(1);
                        }
                    }

                    // If businessModel is KOPYA_BASI and machine list is provided, create them.
                    if ("KOPYA_BASI".equals(businessModel) && body.get("machines") != null) {
                        JsonArray machs = body.get("machines").getAsJsonArray();
                        String insMachine = "INSERT INTO customer_machines (customer_id, serial_number, machine_name, initial_meter, current_meter, initial_meter_date, installation_date, ownership_type) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                        try (PreparedStatement ps = conn.prepareStatement(insMachine)) {
                            for (int i = 0; i < machs.size(); i++) {
                                JsonObject m = machs.get(i).getAsJsonObject();
                                ps.setInt(1, customerId);
                                ps.setString(2, m.get("serialNumber").getAsString());
                                ps.setString(3, m.get("machineName").getAsString());
                                int initial = m.get("initialMeter").getAsInt();
                                ps.setInt(4, initial);
                                ps.setInt(5, initial);
                                ps.setDate(6, m.get("initialMeterDate") != null && !m.get("initialMeterDate").isJsonNull() ? java.sql.Date.valueOf(m.get("initialMeterDate").getAsString()) : new java.sql.Date(System.currentTimeMillis()));
                                ps.setDate(7, m.get("installationDate") != null && !m.get("installationDate").isJsonNull() ? java.sql.Date.valueOf(m.get("installationDate").getAsString()) : new java.sql.Date(System.currentTimeMillis()));
                                ps.setString(8, m.get("ownershipType") != null ? m.get("ownershipType").getAsString() : "BİZİM_MAKİNEMİZ");
                                ps.addBatch();
                            }
                            ps.executeBatch();
                        }
                    }

                    conn.commit();
                    return successResponse("Müşteri başarıyla tanımlandı.");
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                }
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        put("/api/customers/:id", (req, res) -> {
            res.type("application/json");
            int id = Integer.parseInt(req.params(":id"));
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            String code = body.get("customerCode").getAsString();
            String companyName = body.get("companyName").getAsString();
            double unitPrice = body.get("unitPrice").getAsDouble();
            String businessModel = body.get("businessModel").getAsString();
            
            Integer responsibleId = null;
            if (body.get("responsibleId") != null && !body.get("responsibleId").isJsonNull()) {
                responsibleId = body.get("responsibleId").getAsInt();
            }

            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("UPDATE customers SET customer_code = ?, company_name = ?, unit_price = ?, business_model = ?, responsible_employee_id = ? WHERE id = ?")) {
                ps.setString(1, code);
                ps.setString(2, companyName);
                ps.setDouble(3, unitPrice);
                ps.setString(4, businessModel);
                if (responsibleId != null) ps.setInt(5, responsibleId); else ps.setNull(5, Types.INTEGER);
                ps.setInt(6, id);
                if (ps.executeUpdate() > 0) {
                    return successResponse("Müşteri bilgileri güncellendi.");
                }
                return errorResponse(res, 404, "Müşteri bulunamadı.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        delete("/api/customers/:id", (req, res) -> {
            res.type("application/json");
            int id = Integer.parseInt(req.params(":id"));
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM customers WHERE id = ?")) {
                ps.setInt(1, id);
                if (ps.executeUpdate() > 0) {
                    return successResponse("Müşteri silindi.");
                }
                return errorResponse(res, 404, "Müşteri bulunamadı.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        // Customer Details & Financials Summary
        get("/api/customers/:id/details", (req, res) -> {
            res.type("application/json");
            int custId = Integer.parseInt(req.params(":id"));
            JsonObject payload = new JsonObject();
            try (Connection conn = getConnection()) {
                // 1. Fetch customer details
                try (PreparedStatement ps = conn.prepareStatement("SELECT c.id, c.customer_code, c.company_name, c.unit_price, c.business_model FROM customers c WHERE c.id = ?")) {
                    ps.setInt(1, custId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            payload.addProperty("id", rs.getInt("id"));
                            payload.addProperty("customerCode", rs.getString("customer_code"));
                            payload.addProperty("companyName", rs.getString("company_name"));
                            payload.addProperty("unitPrice", rs.getDouble("unit_price"));
                            payload.addProperty("businessModel", rs.getString("business_model"));
                        } else {
                            return errorResponse(res, 404, "Müşteri bulunamadı.");
                        }
                    }
                }

                // 2. Financial Summary
                int totalCopies = 0;
                double totalRevenue = 0.0;
                double totalTonerCost = 0.0;
                double totalServiceExpenses = 0.0;
                double totalMaterialCost = 0.0;

                // Total copies and ciro
                try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(SUM(difference), 0), COALESCE(SUM(total_amount), 0) FROM meter_readings WHERE customer_id = ?")) {
                    ps.setInt(1, custId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            totalCopies = rs.getInt(1);
                            totalRevenue = rs.getDouble(2);
                        }
                    }
                }
                // Toner cost
                try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(SUM(delivery_cost), 0) FROM customer_toner_deliveries WHERE customer_id = ?")) {
                    ps.setInt(1, custId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) totalTonerCost = rs.getDouble(1);
                    }
                }
                // Service expenses
                try (PreparedStatement ps = conn.prepareStatement("SELECT COALESCE(SUM(amount), 0) FROM service_expenses WHERE customer_id = ?")) {
                    ps.setInt(1, custId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) totalServiceExpenses = rs.getDouble(1);
                    }
                }
                // Material dispatches cost (from operations_log)
                String materialCostQuery = "SELECT COALESCE(SUM(ol.calculated_cost), 0) FROM operations_log ol " +
                        "JOIN customer_machines cm ON ol.device_id = cm.id " +
                        "WHERE cm.customer_id = ? AND (ol.operation_type LIKE '%YEDEK_PARCA' OR ol.operation_type LIKE '%PARCA')";
                try (PreparedStatement ps = conn.prepareStatement(materialCostQuery)) {
                    ps.setInt(1, custId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) totalMaterialCost = rs.getDouble(1);
                    }
                }

                JsonObject finance = new JsonObject();
                finance.addProperty("totalCopies", totalCopies);
                finance.addProperty("totalRevenue", totalRevenue);
                finance.addProperty("totalTonerCost", totalTonerCost);
                finance.addProperty("totalServiceExpenses", totalServiceExpenses);
                finance.addProperty("totalMaterialCost", totalMaterialCost);
                finance.addProperty("totalExpenses", totalTonerCost + totalServiceExpenses + totalMaterialCost);
                finance.addProperty("netProfit", totalRevenue - (totalTonerCost + totalServiceExpenses + totalMaterialCost));
                payload.add("financials", finance);

                // 3. Unified Delivery History
                JsonArray history = new JsonArray();
                SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy");

                // Stock Movements
                String querySM = "SELECT m.transaction_date, m.transaction_type, m.quantity, m.description, p.stock_code, p.name, e.first_name, e.last_name " +
                        "FROM stock_movements m JOIN products p ON m.product_id = p.id " +
                        "LEFT JOIN employees e ON m.employee_id = e.id " +
                        "WHERE m.customer_id = ? ORDER BY m.transaction_date DESC";
                try (PreparedStatement ps = conn.prepareStatement(querySM)) {
                    ps.setInt(1, custId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            JsonObject row = new JsonObject();
                            row.addProperty("date", sdf.format(rs.getTimestamp("transaction_date")));
                            row.addProperty("rawDate", rs.getTimestamp("transaction_date").getTime());
                            
                            String type = rs.getString("transaction_type");
                            String txDisp = "Depodan Doğrudan Teslimat";
                            if ("EMPLOYEE_TO_CUSTOMER".equals(type)) txDisp = "Personel Zimmetinden Teslimat";
                            
                            row.addProperty("type", txDisp);
                            row.addProperty("handler", rs.getString("first_name") != null ? rs.getString("first_name") + " " + rs.getString("last_name") : "Ana Depo");
                            row.addProperty("stockCode", rs.getString("stock_code"));
                            row.addProperty("productName", rs.getString("name"));
                            row.addProperty("quantity", String.valueOf(rs.getInt("quantity")));
                            row.addProperty("desc", rs.getString("description"));
                            history.add(row);
                        }
                    }
                }

                // Toner Deliveries
                String queryTD = "SELECT td.delivery_date, td.delivered_grams, td.notes, tt.toner_name FROM customer_toner_deliveries td " +
                        "JOIN toner_types tt ON td.toner_type_id = tt.id WHERE td.customer_id = ? ORDER BY td.delivery_date DESC";
                try (PreparedStatement ps = conn.prepareStatement(queryTD)) {
                    ps.setInt(1, custId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            JsonObject row = new JsonObject();
                            row.addProperty("date", sdf.format(rs.getTimestamp("delivery_date")));
                            row.addProperty("rawDate", rs.getTimestamp("delivery_date").getTime());
                            row.addProperty("type", "Toner Teslimatı");
                            row.addProperty("handler", "Ana Depo");
                            row.addProperty("stockCode", "-");
                            row.addProperty("productName", rs.getString("toner_name"));
                            row.addProperty("quantity", rs.getInt("delivered_grams") + " gr");
                            row.addProperty("desc", rs.getString("notes") != null ? rs.getString("notes") : "-");
                            history.add(row);
                        }
                    }
                }

                // Spare Part operations (from operations_log)
                String queryOP = "SELECT ol.operation_date, ol.operation_type, ol.quantity, ol.material_description, p.name AS prod_name " +
                        "FROM operations_log ol JOIN customer_machines cm ON ol.device_id = cm.id " +
                        "LEFT JOIN products p ON ol.product_id = p.id " +
                        "WHERE cm.customer_id = ? AND (ol.operation_type LIKE '%YEDEK_PARCA' OR ol.operation_type LIKE '%PARCA') " +
                        "ORDER BY ol.operation_date DESC";
                try (PreparedStatement ps = conn.prepareStatement(queryOP)) {
                    ps.setInt(1, custId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            JsonObject row = new JsonObject();
                            row.addProperty("date", sdf.format(rs.getTimestamp("operation_date")));
                            row.addProperty("rawDate", rs.getTimestamp("operation_date").getTime());
                            
                            String type = rs.getString("operation_type");
                            String txDisp = "Yedek Parça (Sözleşmeli)";
                            if ("NORMAL_YEDEK_PARCA".equals(type)) txDisp = "Yedek Parça (Ücretli Satış)";
                            
                            row.addProperty("type", txDisp);
                            row.addProperty("handler", "Saha Tekniker");
                            row.addProperty("stockCode", "-");
                            row.addProperty("productName", rs.getString("prod_name") != null ? rs.getString("prod_name") : "Yedek Parça");
                            row.addProperty("quantity", String.valueOf(rs.getInt("quantity")));
                            row.addProperty("desc", rs.getString("material_description"));
                            history.add(row);
                        }
                    }
                }

                // Sort history by date descending
                List<JsonObject> list = new ArrayList<>();
                for (int i = 0; i < history.size(); i++) {
                    list.add(history.get(i).getAsJsonObject());
                }
                list.sort((a, b) -> Long.compare(b.get("rawDate").getAsLong(), a.get("rawDate").getAsLong()));
                
                JsonArray sortedHistory = new JsonArray();
                for (JsonObject item : list) sortedHistory.add(item);
                payload.add("history", sortedHistory);

                return successResponse(payload);
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        // Direct Delivery from Warehouse/Employee to Customer
        post("/api/customers/delivery", (req, res) -> {
            res.type("application/json");
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            int productId = body.get("productId").getAsInt();
            int customerId = body.get("customerId").getAsInt();
            int qty = body.get("qty").getAsInt();
            String desc = body.get("description") != null ? body.get("description").getAsString() : "";
            boolean fromWarehouse = body.get("fromWarehouse").getAsBoolean();
            
            Integer employeeId = null;
            if (!fromWarehouse && body.get("employeeId") != null) {
                employeeId = body.get("employeeId").getAsInt();
            }

            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                try {
                    if (fromWarehouse) {
                        // Check warehouse quantity
                        int currentStock = 0;
                        try (PreparedStatement check = conn.prepareStatement("SELECT warehouse_quantity FROM products WHERE id = ?")) {
                            check.setInt(1, productId);
                            try (ResultSet rs = check.executeQuery()) {
                                if (rs.next()) currentStock = rs.getInt(1);
                            }
                        }
                        if (qty > currentStock) {
                            return errorResponse(res, 400, "Depoda yeterli ürün stoğu bulunmamaktadır! Mevcut Depo Stoğu: " + currentStock);
                        }

                        // Decrement warehouse quantity
                        try (PreparedStatement ps = conn.prepareStatement("UPDATE products SET warehouse_quantity = warehouse_quantity - ? WHERE id = ?")) {
                            ps.setInt(1, qty);
                            ps.setInt(2, productId);
                            ps.executeUpdate();
                        }

                        // Log movement
                        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO stock_movements (transaction_type, product_id, customer_id, quantity, description) VALUES ('WAREHOUSE_TO_CUSTOMER', ?, ?, ?, ?)")) {
                            ps.setInt(1, productId);
                            ps.setInt(2, customerId);
                            ps.setInt(3, qty);
                            ps.setString(4, desc.isEmpty() ? "Depodan müşteriye teslimat" : desc);
                            ps.executeUpdate();
                        }
                    } else {
                        // Check employee handover quantity
                        int currentStock = 0;
                        try (PreparedStatement check = conn.prepareStatement("SELECT quantity FROM employee_handovers WHERE employee_id = ? AND product_id = ?")) {
                            check.setInt(1, employeeId);
                            check.setInt(2, productId);
                            try (ResultSet rs = check.executeQuery()) {
                                if (rs.next()) currentStock = rs.getInt(1);
                            }
                        }
                        if (qty > currentStock) {
                            return errorResponse(res, 400, "Çalışanın zimmetinde yeterli ürün stoğu bulunmamaktadır! Mevcut Zimmet Stoğu: " + currentStock);
                        }

                        // Decrement employee quantity
                        try (PreparedStatement ps = conn.prepareStatement("UPDATE employee_handovers SET quantity = quantity - ? WHERE employee_id = ? AND product_id = ?")) {
                            ps.setInt(1, qty);
                            ps.setInt(2, employeeId);
                            ps.setInt(3, productId);
                            ps.executeUpdate();
                        }
                        // Delete empty handovers
                        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM employee_handovers WHERE employee_id = ? AND product_id = ? AND quantity <= 0")) {
                            ps.setInt(1, employeeId);
                            ps.setInt(2, productId);
                            ps.executeUpdate();
                        }

                        // Log movement
                        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO stock_movements (transaction_type, product_id, employee_id, customer_id, quantity, description) VALUES ('EMPLOYEE_TO_CUSTOMER', ?, ?, ?, ?, ?)")) {
                            ps.setInt(1, productId);
                            ps.setInt(2, employeeId);
                            ps.setInt(3, customerId);
                            ps.setInt(4, qty);
                            ps.setString(5, desc.isEmpty() ? "Zimmetten müşteriye teslimat" : desc);
                            ps.executeUpdate();
                        }
                    }

                    // Increment customer deliveries (responsibilities)
                    boolean exists = false;
                    try (PreparedStatement check = conn.prepareStatement("SELECT COUNT(*) FROM customer_deliveries WHERE customer_id = ? AND product_id = ?")) {
                        check.setInt(1, customerId);
                        check.setInt(2, productId);
                        try (ResultSet rs = check.executeQuery()) {
                            if (rs.next() && rs.getInt(1) > 0) exists = true;
                        }
                    }

                    if (exists) {
                        try (PreparedStatement ps = conn.prepareStatement("UPDATE customer_deliveries SET quantity = quantity + ? WHERE customer_id = ? AND product_id = ?")) {
                            ps.setInt(1, qty);
                            ps.setInt(2, customerId);
                            ps.setInt(3, productId);
                            ps.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO customer_deliveries (customer_id, product_id, quantity) VALUES (?, ?, ?)")) {
                            ps.setInt(1, customerId);
                            ps.setInt(2, productId);
                            ps.setInt(3, qty);
                            ps.executeUpdate();
                        }
                    }

                    conn.commit();
                    return successResponse("Teslimat başarıyla kaydedildi.");
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                }
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        // Customer Machines
        get("/api/customer-machines", (req, res) -> {
            res.type("application/json");
            JsonArray arr = new JsonArray();
            String query = "SELECT m.id, m.customer_id, m.serial_number, m.machine_name, m.initial_meter, m.current_meter, m.initial_meter_date, m.installation_date, m.ownership_type, c.company_name, " +
                    "(SELECT COALESCE(SUM(mr.difference), 0) FROM meter_readings mr WHERE mr.machine_id = m.id) AS total_copies, " +
                    "(SELECT COALESCE(SUM(mr.total_amount), 0) FROM meter_readings mr WHERE mr.machine_id = m.id) AS total_revenue " +
                    "FROM customer_machines m JOIN customers c ON m.customer_id = c.id " +
                    "ORDER BY c.company_name, m.machine_name ASC";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(query);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject m = new JsonObject();
                    m.addProperty("id", rs.getInt("id"));
                    m.addProperty("customerId", rs.getInt("customer_id"));
                    m.addProperty("companyName", rs.getString("company_name"));
                    m.addProperty("machineName", rs.getString("machine_name"));
                    m.addProperty("serialNumber", rs.getString("serial_number"));
                    m.addProperty("initialMeter", rs.getInt("initial_meter"));
                    m.addProperty("currentMeter", rs.getInt("current_meter"));
                    m.addProperty("initialMeterDate", rs.getDate("initial_meter_date") != null ? rs.getDate("initial_meter_date").toString() : "");
                    m.addProperty("installationDate", rs.getDate("installation_date") != null ? rs.getDate("installation_date").toString() : "");
                    m.addProperty("ownershipType", rs.getString("ownership_type"));
                    m.addProperty("totalCopies", rs.getInt("total_copies"));
                    m.addProperty("totalRevenue", rs.getDouble("total_revenue"));
                    arr.add(m);
                }
                return successResponse(arr);
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        get("/api/customers/:id/machines", (req, res) -> {
            res.type("application/json");
            int custId = Integer.parseInt(req.params(":id"));
            JsonArray arr = new JsonArray();
            String query = "SELECT id, serial_number, machine_name, initial_meter, current_meter, initial_meter_date, installation_date, ownership_type FROM customer_machines WHERE customer_id = ?";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setInt(1, custId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject m = new JsonObject();
                        m.addProperty("id", rs.getInt("id"));
                        m.addProperty("serialNumber", rs.getString("serial_number"));
                        m.addProperty("machineName", rs.getString("machine_name"));
                        m.addProperty("initialMeter", rs.getInt("initial_meter"));
                        m.addProperty("currentMeter", rs.getInt("current_meter"));
                        m.addProperty("initialMeterDate", rs.getDate("initial_meter_date") != null ? rs.getDate("initial_meter_date").toString() : "");
                        m.addProperty("installationDate", rs.getDate("installation_date") != null ? rs.getDate("installation_date").toString() : "");
                        m.addProperty("ownershipType", rs.getString("ownership_type"));
                        arr.add(m);
                    }
                }
                return successResponse(arr);
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        post("/api/customer-machines", (req, res) -> {
            res.type("application/json");
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            int customerId = body.get("customerId").getAsInt();
            String serialNumber = body.get("serialNumber").getAsString();
            String machineName = body.get("machineName").getAsString();
            int initialMeter = body.get("initialMeter").getAsInt();
            String initialMeterDate = body.get("initialMeterDate") != null ? body.get("initialMeterDate").getAsString() : "";
            String installationDate = body.get("installationDate") != null ? body.get("installationDate").getAsString() : "";
            String ownershipType = body.get("ownershipType") != null ? body.get("ownershipType").getAsString() : "BİZİM_MAKİNEMİZ";

            String query = "INSERT INTO customer_machines (customer_id, serial_number, machine_name, initial_meter, current_meter, initial_meter_date, installation_date, ownership_type) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setInt(1, customerId);
                ps.setString(2, serialNumber);
                ps.setString(3, machineName);
                ps.setInt(4, initialMeter);
                ps.setInt(5, initialMeter); // Current starts at initial
                
                if (!initialMeterDate.isEmpty()) ps.setDate(6, java.sql.Date.valueOf(initialMeterDate));
                else ps.setDate(6, new java.sql.Date(System.currentTimeMillis()));
                
                if (!installationDate.isEmpty()) ps.setDate(7, java.sql.Date.valueOf(installationDate));
                else ps.setDate(7, new java.sql.Date(System.currentTimeMillis()));
                
                ps.setString(8, ownershipType);
                ps.executeUpdate();
                return successResponse("Cihaz kaydedildi.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        put("/api/customer-machines/:id", (req, res) -> {
            res.type("application/json");
            int id = Integer.parseInt(req.params(":id"));
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            int customerId = body.get("customerId").getAsInt();
            String serialNumber = body.get("serialNumber").getAsString();
            String machineName = body.get("machineName").getAsString();
            int initialMeter = body.get("initialMeter").getAsInt();
            int currentMeter = body.get("currentMeter").getAsInt();
            String initialMeterDate = body.get("initialMeterDate") != null ? body.get("initialMeterDate").getAsString() : "";
            String installationDate = body.get("installationDate") != null ? body.get("installationDate").getAsString() : "";
            String ownershipType = body.get("ownershipType").getAsString();

            String query = "UPDATE customer_machines SET customer_id = ?, serial_number = ?, machine_name = ?, initial_meter = ?, current_meter = ?, initial_meter_date = ?, installation_date = ?, ownership_type = ? WHERE id = ?";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setInt(1, customerId);
                ps.setString(2, serialNumber);
                ps.setString(3, machineName);
                ps.setInt(4, initialMeter);
                ps.setInt(5, currentMeter);
                
                if (!initialMeterDate.isEmpty()) ps.setDate(6, java.sql.Date.valueOf(initialMeterDate));
                else ps.setNull(6, Types.DATE);
                
                if (!installationDate.isEmpty()) ps.setDate(7, java.sql.Date.valueOf(installationDate));
                else ps.setNull(7, Types.DATE);
                
                ps.setString(8, ownershipType);
                ps.setInt(9, id);
                
                if (ps.executeUpdate() > 0) {
                    return successResponse("Cihaz güncellendi.");
                }
                return errorResponse(res, 404, "Cihaz bulunamadı.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        delete("/api/customer-machines/:id", (req, res) -> {
            res.type("application/json");
            int id = Integer.parseInt(req.params(":id"));
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM customer_machines WHERE id = ?")) {
                ps.setInt(1, id);
                if (ps.executeUpdate() > 0) {
                    return successResponse("Cihaz silindi.");
                }
                return errorResponse(res, 404, "Cihaz bulunamadı.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        // Meter Readings for specific Customer Machine
        get("/api/customer-machines/:id/meter-readings", (req, res) -> {
            res.type("application/json");
            int machId = Integer.parseInt(req.params(":id"));
            JsonArray arr = new JsonArray();
            String query = "SELECT id, reading_date, meter_value, previous_meter_value, difference, unit_price, total_amount FROM meter_readings WHERE machine_id = ? ORDER BY reading_date DESC";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setInt(1, machId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject mr = new JsonObject();
                        mr.addProperty("id", rs.getInt("id"));
                        mr.addProperty("readingDate", rs.getTimestamp("reading_date").toString());
                        mr.addProperty("meterValue", rs.getInt("meter_value"));
                        mr.addProperty("previousMeterValue", rs.getInt("previous_meter_value"));
                        mr.addProperty("difference", rs.getInt("difference"));
                        mr.addProperty("unitPrice", rs.getDouble("unit_price"));
                        mr.addProperty("totalAmount", rs.getDouble("total_amount"));
                        arr.add(mr);
                    }
                }
                return successResponse(arr);
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        post("/api/customer-machines/:id/meter-readings", (req, res) -> {
            res.type("application/json");
            int machId = Integer.parseInt(req.params(":id"));
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            int meterValue = body.get("meterValue").getAsInt();

            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                try {
                    // Fetch machine details
                    int customerId = 0;
                    int previousMeter = 0;
                    try (PreparedStatement check = conn.prepareStatement("SELECT customer_id, current_meter FROM customer_machines WHERE id = ?")) {
                        check.setInt(1, machId);
                        try (ResultSet rs = check.executeQuery()) {
                            if (rs.next()) {
                                customerId = rs.getInt("customer_id");
                                previousMeter = rs.getInt("current_meter");
                            } else {
                                return errorResponse(res, 404, "Cihaz bulunamadı.");
                            }
                        }
                    }

                    if (meterValue < previousMeter) {
                        return errorResponse(res, 400, "Yeni sayaç değeri öncekine eşit veya küçük olamaz! Önceki: " + previousMeter);
                    }

                    // Get unit price from customer
                    double unitPrice = 0.1500;
                    try (PreparedStatement check = conn.prepareStatement("SELECT unit_price FROM customers WHERE id = ?")) {
                        check.setInt(1, customerId);
                        try (ResultSet rs = check.executeQuery()) {
                            if (rs.next()) unitPrice = rs.getDouble("unit_price");
                        }
                    }

                    int difference = meterValue - previousMeter;
                    double totalAmount = difference * unitPrice;

                    // Insert meter reading
                    String insQuery = "INSERT INTO meter_readings (customer_id, machine_id, meter_value, previous_meter_value, difference, unit_price, total_amount) VALUES (?, ?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(insQuery)) {
                        ps.setInt(1, customerId);
                        ps.setInt(2, machId);
                        ps.setInt(3, meterValue);
                        ps.setInt(4, previousMeter);
                        ps.setInt(5, difference);
                        ps.setDouble(6, unitPrice);
                        ps.setDouble(7, totalAmount);
                        ps.executeUpdate();
                    }

                    // Update customer current_meter
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE customer_machines SET current_meter = ? WHERE id = ?")) {
                        ps.setInt(1, meterValue);
                        ps.setInt(2, machId);
                        ps.executeUpdate();
                    }

                    conn.commit();
                    return successResponse("Sayaç okuma kaydı başarıyla eklendi.");
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                }
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        // Toner Deliveries endpoint (Customer detail module)
        get("/api/customers/:id/toners", (req, res) -> {
            res.type("application/json");
            int custId = Integer.parseInt(req.params(":id"));
            JsonArray arr = new JsonArray();
            
            // To evaluate capacity we join and sum reading differences since delivery date.
            // Simplified calculation like Java:
            String query = "SELECT td.id, td.delivery_date, td.delivered_grams, td.delivery_cost, td.notes, tt.toner_name, tt.standard_page_capacity " +
                    "FROM customer_toner_deliveries td JOIN toner_types tt ON td.toner_type_id = tt.id " +
                    "WHERE td.customer_id = ? ORDER BY td.delivery_date DESC";
            
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setInt(1, custId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject td = new JsonObject();
                        int delId = rs.getInt("id");
                        Timestamp delDate = rs.getTimestamp("delivery_date");
                        int grams = rs.getInt("delivered_grams");
                        double cost = rs.getDouble("delivery_cost");
                        String notes = rs.getString("notes");
                        String tonerName = rs.getString("toner_name");

                        // Expected capacity: 1g = 20 pages
                        int expected = grams * 20;

                        // Calculate actual copies since this toner delivery date.
                        // Query the sum of difference from meter readings since that date
                        int actualCopies = 0;
                        try (PreparedStatement ps2 = conn.prepareStatement("SELECT COALESCE(SUM(difference), 0) FROM meter_readings WHERE customer_id = ? AND reading_date >= ?")) {
                            ps2.setInt(1, custId);
                            ps2.setTimestamp(2, delDate);
                            try (ResultSet rs2 = ps2.executeQuery()) {
                                if (rs2.next()) actualCopies = rs2.getInt(1);
                            }
                        }

                        int remaining = expected - actualCopies;
                        String status = "Aktif (Kalan: " + remaining + ")";
                        if (remaining <= 0) {
                            status = "Tükendi (Aşıldı: " + Math.abs(remaining) + ")";
                        }

                        td.addProperty("id", delId);
                        td.addProperty("date", delDate.toString());
                        td.addProperty("tonerName", tonerName);
                        td.addProperty("grams", grams);
                        td.addProperty("expectedPages", expected);
                        td.addProperty("actualCopies", actualCopies);
                        td.addProperty("remaining", remaining);
                        td.addProperty("cost", cost);
                        td.addProperty("status", status);
                        td.addProperty("notes", notes != null ? notes : "-");
                        arr.add(td);
                    }
                }
                return successResponse(arr);
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        post("/api/customers/:id/toners", (req, res) -> {
            res.type("application/json");
            int custId = Integer.parseInt(req.params(":id"));
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            int tonerTypeId = body.get("tonerTypeId").getAsInt();
            int grams = body.get("grams").getAsInt();
            String notes = body.get("notes") != null ? body.get("notes").getAsString() : "";

            try (Connection conn = getConnection()) {
                double unitKgCost = 0.0;
                try (PreparedStatement check = conn.prepareStatement("SELECT unit_kg_cost FROM toner_types WHERE id = ?")) {
                    check.setInt(1, tonerTypeId);
                    try (ResultSet rs = check.executeQuery()) {
                        if (rs.next()) unitKgCost = rs.getDouble("unit_kg_cost");
                    }
                }
                double cost = (unitKgCost / 1000.0) * grams;

                String query = "INSERT INTO customer_toner_deliveries (customer_id, toner_type_id, delivered_grams, delivery_cost, notes) VALUES (?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(query)) {
                    ps.setInt(1, custId);
                    ps.setInt(2, tonerTypeId);
                    ps.setInt(3, grams);
                    ps.setDouble(4, cost);
                    ps.setString(5, notes);
                    ps.executeUpdate();
                }
                return successResponse("Toner teslimatı eklendi.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        delete("/api/customers/toners/:id", (req, res) -> {
            res.type("application/json");
            int id = Integer.parseInt(req.params(":id"));
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM customer_toner_deliveries WHERE id = ?")) {
                ps.setInt(1, id);
                if (ps.executeUpdate() > 0) {
                    return successResponse("Toner teslimat kaydı silindi.");
                }
                return errorResponse(res, 404, "Toner teslimat kaydı bulunamadı.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        // Service Expenses Endpoint
        get("/api/customers/:id/services", (req, res) -> {
            res.type("application/json");
            int custId = Integer.parseInt(req.params(":id"));
            JsonArray arr = new JsonArray();
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("SELECT id, description, amount, expense_date FROM service_expenses WHERE customer_id = ? ORDER BY expense_date DESC")) {
                ps.setInt(1, custId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject s = new JsonObject();
                        s.addProperty("id", rs.getInt("id"));
                        s.addProperty("date", rs.getTimestamp("expense_date").toString());
                        s.addProperty("desc", rs.getString("description"));
                        s.addProperty("amount", rs.getDouble("amount"));
                        arr.add(s);
                    }
                }
                return successResponse(arr);
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        post("/api/customers/:id/services", (req, res) -> {
            res.type("application/json");
            int custId = Integer.parseInt(req.params(":id"));
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            String desc = body.get("description").getAsString();
            double amount = body.get("amount").getAsDouble();

            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("INSERT INTO service_expenses (customer_id, description, amount) VALUES (?, ?, ?)")) {
                ps.setInt(1, custId);
                ps.setString(2, desc);
                ps.setDouble(3, amount);
                ps.executeUpdate();
                return successResponse("Servis gideri kaydedildi.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        delete("/api/customers/services/:id", (req, res) -> {
            res.type("application/json");
            int id = Integer.parseInt(req.params(":id"));
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM service_expenses WHERE id = ?")) {
                ps.setInt(1, id);
                if (ps.executeUpdate() > 0) {
                    return successResponse("Servis gideri silindi.");
                }
                return errorResponse(res, 404, "Servis gideri bulunamadı.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        // Spare Part Operations Log (Malzeme Çıkışı - Customer card view)
        get("/api/customers/:id/dispatches", (req, res) -> {
            res.type("application/json");
            int custId = Integer.parseInt(req.params(":id"));
            JsonArray arr = new JsonArray();
            String query = "SELECT ol.id, ol.operation_date, ol.material_description, ol.calculated_cost, ol.billed_amount, ol.quantity, " +
                    "cm.serial_number, cm.machine_name AS brand_model, " +
                    "p.name AS prod_name " +
                    "FROM operations_log ol " +
                    "JOIN customer_machines cm ON ol.device_id = cm.id " +
                    "LEFT JOIN products p ON ol.product_id = p.id " +
                    "WHERE cm.customer_id = ? AND (ol.operation_type LIKE '%YEDEK_PARCA' OR ol.operation_type LIKE '%PARCA') " +
                    "ORDER BY ol.operation_date DESC";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setInt(1, custId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject op = new JsonObject();
                        op.addProperty("id", rs.getInt("id"));
                        op.addProperty("date", rs.getTimestamp("operation_date").toString());
                        op.addProperty("machine", rs.getString("brand_model") + " (" + rs.getString("serial_number") + ")");
                        op.addProperty("productName", rs.getString("prod_name") != null ? rs.getString("prod_name") : "-");
                        op.addProperty("quantity", rs.getInt("quantity"));
                        op.addProperty("desc", rs.getString("material_description"));
                        op.addProperty("cost", rs.getDouble("calculated_cost"));
                        op.addProperty("billed", rs.getDouble("billed_amount"));
                        arr.add(op);
                    }
                }
                return successResponse(arr);
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        post("/api/customers/:id/dispatches", (req, res) -> {
            res.type("application/json");
            int custId = Integer.parseInt(req.params(":id"));
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            
            int machineId = body.get("machineId").getAsInt();
            int productId = body.get("productId").getAsInt();
            int qty = body.get("qty").getAsInt();
            String desc = body.get("description") != null ? body.get("description").getAsString() : "";
            double cost = body.get("cost") != null ? body.get("cost").getAsDouble() : 0.0;
            boolean fromWarehouse = body.get("fromWarehouse").getAsBoolean();
            
            Integer employeeId = null;
            if (!fromWarehouse && body.get("employeeId") != null) {
                employeeId = body.get("employeeId").getAsInt();
            }

            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                try {
                    // Check stock first
                    int currentStock = 0;
                    if (fromWarehouse) {
                        try (PreparedStatement check = conn.prepareStatement("SELECT warehouse_quantity FROM products WHERE id = ?")) {
                            check.setInt(1, productId);
                            try (ResultSet rs = check.executeQuery()) {
                                if (rs.next()) currentStock = rs.getInt(1);
                            }
                        }
                    } else {
                        try (PreparedStatement check = conn.prepareStatement("SELECT quantity FROM employee_handovers WHERE employee_id = ? AND product_id = ?")) {
                            check.setInt(1, employeeId);
                            check.setInt(2, productId);
                            try (ResultSet rs = check.executeQuery()) {
                                if (rs.next()) currentStock = rs.getInt(1);
                            }
                        }
                    }

                    if (qty > currentStock) {
                        return errorResponse(res, 400, "Yeterli ürün stoğu bulunmamaktadır! Stok: " + currentStock);
                    }

                    // Decrement stock
                    if (fromWarehouse) {
                        try (PreparedStatement ps = conn.prepareStatement("UPDATE products SET warehouse_quantity = warehouse_quantity - ? WHERE id = ?")) {
                            ps.setInt(1, qty);
                            ps.setInt(2, productId);
                            ps.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement ps = conn.prepareStatement("UPDATE employee_handovers SET quantity = quantity - ? WHERE employee_id = ? AND product_id = ?")) {
                            ps.setInt(1, qty);
                            ps.setInt(2, employeeId);
                            ps.setInt(3, productId);
                            ps.executeUpdate();
                        }
                        // Delete empty handovers
                        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM employee_handovers WHERE employee_id = ? AND product_id = ? AND quantity <= 0")) {
                            ps.setInt(1, employeeId);
                            ps.setInt(2, productId);
                            ps.executeUpdate();
                        }
                    }

                    // Fetch customer business model to log correct operations log type
                    String businessModel = "NORMAL";
                    try (PreparedStatement ps = conn.prepareStatement("SELECT business_model FROM customers WHERE id = ?")) {
                        ps.setInt(1, custId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) businessModel = rs.getString("business_model");
                        }
                    }

                    String opType = "NORMAL_YEDEK_PARCA";
                    double totalCost = cost * qty;
                    double billedAmount = 0.0;

                    if ("KOPYA_BASI".equals(businessModel)) {
                        opType = "KOPYA_BASI_YEDEK_PARCA";
                        billedAmount = 0.0;
                    } else if ("MALZEME_KARSILIGI".equals(businessModel)) {
                        opType = "MALZEME_KARSILIGI_YEDEK_PARCA";
                        billedAmount = 0.0;
                    } else {
                        // NORMAL - billed to customer
                        billedAmount = totalCost;
                        totalCost = 0.0; // Sales cost is zero internally
                    }

                    // Log operation
                    String insLog = "INSERT INTO operations_log (device_id, operation_type, product_id, quantity, material_description, calculated_cost, billed_amount, expected_pages) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(insLog)) {
                        ps.setInt(1, machineId);
                        ps.setString(2, opType);
                        ps.setInt(3, productId);
                        ps.setInt(4, qty);
                        ps.setString(5, desc.isEmpty() ? "Yedek Parça Çıkışı" : desc);
                        ps.setDouble(6, totalCost);
                        ps.setDouble(7, billedAmount);
                        ps.setInt(8, 0);
                        ps.executeUpdate();
                    }

                    // Log stock movement
                    String mvType = fromWarehouse ? "WAREHOUSE_TO_CUSTOMER" : "EMPLOYEE_TO_CUSTOMER";
                    String mvDesc = fromWarehouse ? "Malzeme Çıkışı (Depo) - Cihaz ID: " + machineId : "Malzeme Çıkışı (Zimmet) - Cihaz ID: " + machineId;
                    
                    String insMov = "INSERT INTO stock_movements (transaction_type, product_id, employee_id, customer_id, quantity, description) VALUES (?, ?, ?, ?, ?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(insMov)) {
                        ps.setString(1, mvType);
                        ps.setInt(2, productId);
                        if (employeeId != null) ps.setInt(3, employeeId); else ps.setNull(3, Types.INTEGER);
                        ps.setInt(4, custId);
                        ps.setInt(5, qty);
                        ps.setString(6, mvDesc);
                        ps.executeUpdate();
                    }

                    conn.commit();
                    return successResponse("Malzeme/Parça çıkışı kaydedildi.");
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                }
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        delete("/api/customers/dispatches/:id", (req, res) -> {
            res.type("application/json");
            int opLogId = Integer.parseInt(req.params(":id"));
            try (Connection conn = getConnection()) {
                conn.setAutoCommit(false);
                try {
                    // Fetch operation log info
                    int deviceId = 0;
                    String opType = "";
                    Integer productId = null;
                    int quantity = 0;
                    try (PreparedStatement check = conn.prepareStatement("SELECT device_id, operation_type, product_id, quantity FROM operations_log WHERE id = ?")) {
                        check.setInt(1, opLogId);
                        try (ResultSet rs = check.executeQuery()) {
                            if (rs.next()) {
                                deviceId = rs.getInt("device_id");
                                opType = rs.getString("operation_type");
                                int pId = rs.getInt("product_id");
                                productId = rs.wasNull() ? null : pId;
                                quantity = rs.getInt("quantity");
                            } else {
                                return errorResponse(res, 404, "Operasyon kaydı bulunamadı.");
                            }
                        }
                    }

                    // Restore stock if it was a spare part exit
                    boolean isSpare = "MALZEME_KARSILIGI".equals(opType) || opType.endsWith("_YEDEK_PARCA");
                    if (isSpare && productId != null && quantity > 0) {
                        int customerId = 0;
                        try (PreparedStatement ps = conn.prepareStatement("SELECT customer_id FROM customer_machines WHERE id = ?")) {
                            ps.setInt(1, deviceId);
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) customerId = rs.getInt(1);
                            }
                        }

                        // Determine movement path from stock_movements
                        String txType = "";
                        Integer employeeId = null;
                        try (PreparedStatement ps = conn.prepareStatement("SELECT transaction_type, employee_id FROM stock_movements WHERE product_id = ? AND customer_id = ? AND quantity = ? ORDER BY id DESC LIMIT 1")) {
                            ps.setInt(1, productId);
                            ps.setInt(2, customerId);
                            ps.setInt(3, quantity);
                            try (ResultSet rs = ps.executeQuery()) {
                                if (rs.next()) {
                                    txType = rs.getString("transaction_type");
                                    int empId = rs.getInt("employee_id");
                                    employeeId = rs.wasNull() ? null : empId;
                                }
                            }
                        }

                        if ("WAREHOUSE_TO_CUSTOMER".equals(txType)) {
                            // Restore to warehouse
                            try (PreparedStatement ps = conn.prepareStatement("UPDATE products SET warehouse_quantity = warehouse_quantity + ? WHERE id = ?")) {
                                ps.setInt(1, quantity);
                                ps.setInt(2, productId);
                                ps.executeUpdate();
                            }
                        } else if ("EMPLOYEE_TO_CUSTOMER".equals(txType) && employeeId != null) {
                            // Restore to employee handovers
                            boolean hasHandover = false;
                            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM employee_handovers WHERE employee_id = ? AND product_id = ?")) {
                                ps.setInt(1, employeeId);
                                ps.setInt(2, productId);
                                try (ResultSet rs = ps.executeQuery()) {
                                    if (rs.next() && rs.getInt(1) > 0) hasHandover = true;
                                }
                            }

                            if (hasHandover) {
                                try (PreparedStatement ps = conn.prepareStatement("UPDATE employee_handovers SET quantity = quantity + ? WHERE employee_id = ? AND product_id = ?")) {
                                    ps.setInt(1, quantity);
                                    ps.setInt(2, employeeId);
                                    ps.setInt(3, productId);
                                    ps.executeUpdate();
                                }
                            } else {
                                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO employee_handovers (employee_id, product_id, quantity) VALUES (?, ?, ?)")) {
                                    ps.setInt(1, employeeId);
                                    ps.setInt(2, productId);
                                    ps.setInt(3, quantity);
                                    ps.executeUpdate();
                                }
                            }
                        }

                        // Delete stock movement
                        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM stock_movements WHERE product_id = ? AND customer_id = ? AND quantity = ? ORDER BY id DESC LIMIT 1")) {
                            ps.setInt(1, productId);
                            ps.setInt(2, customerId);
                            ps.setInt(3, quantity);
                            ps.executeUpdate();
                        }
                    }

                    // Delete operation log row
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM operations_log WHERE id = ?")) {
                        ps.setInt(1, opLogId);
                        ps.executeUpdate();
                    }

                    conn.commit();
                    return successResponse("Parça çıkış kaydı başarıyla iptal edildi ve stok geri yüklendi.");
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                }
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        // Stock History (Movements)
        get("/api/stock-movements", (req, res) -> {
            res.type("application/json");
            String startStr = req.queryParams("start");
            String endStr = req.queryParams("end");
            
            JsonArray arr = new JsonArray();
            String query = "SELECT m.id, m.transaction_type, m.quantity, m.transaction_date, m.description, " +
                    "p.stock_code, p.name AS product_name, " +
                    "e.first_name, e.last_name, " +
                    "c.company_name " +
                    "FROM stock_movements m " +
                    "JOIN products p ON m.product_id = p.id " +
                    "LEFT JOIN employees e ON m.employee_id = e.id " +
                    "LEFT JOIN customers c ON m.customer_id = c.id " +
                    "WHERE 1=1 ";
            
            if (startStr != null && !startStr.isEmpty()) query += "AND m.transaction_date >= ? ";
            if (endStr != null && !endStr.isEmpty()) query += "AND m.transaction_date <= ? ";
            query += "ORDER BY m.transaction_date DESC";

            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(query)) {
                
                int paramIdx = 1;
                if (startStr != null && !startStr.isEmpty()) {
                    ps.setTimestamp(paramIdx++, Timestamp.valueOf(startStr + " 00:00:00"));
                }
                if (endStr != null && !endStr.isEmpty()) {
                    ps.setTimestamp(paramIdx++, Timestamp.valueOf(endStr + " 23:59:59"));
                }

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject m = new JsonObject();
                        m.addProperty("id", rs.getInt("id"));
                        
                        String rawType = rs.getString("transaction_type");
                        String displayType = rawType;
                        if ("WAREHOUSE_ENTRY".equals(rawType)) displayType = "Depo Girişi";
                        else if ("WAREHOUSE_TO_EMPLOYEE".equals(rawType)) displayType = "Zimmete Aktarım";
                        else if ("EMPLOYEE_TO_CUSTOMER".equals(rawType)) displayType = "Zimmetten Müşteriye";
                        else if ("WAREHOUSE_TO_CUSTOMER".equals(rawType)) displayType = "Depodan Müşteriye";

                        m.addProperty("transactionType", displayType);
                        m.addProperty("stockCode", rs.getString("stock_code"));
                        m.addProperty("productName", rs.getString("product_name"));
                        m.addProperty("quantity", rs.getInt("quantity"));
                        m.addProperty("transactionDate", rs.getTimestamp("transaction_date").toString());
                        m.addProperty("description", rs.getString("description") != null ? rs.getString("description") : "-");
                        
                        String empName = rs.getString("first_name");
                        m.addProperty("employeeName", empName != null ? empName + " " + rs.getString("last_name") : "-");
                        m.addProperty("customerName", rs.getString("company_name") != null ? rs.getString("company_name") : "-");
                        arr.add(m);
                    }
                }
                return successResponse(arr);
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        // Yearly Copy Report
        get("/api/reports/yearly", (req, res) -> {
            res.type("application/json");
            int year = Integer.parseInt(req.queryParams("year"));
            
            // Generate list of months and customer copy stats for that year
            JsonArray arr = new JsonArray();
            String query = "SELECT c.id, c.customer_code, c.company_name, " +
                    "e.first_name, e.last_name, " +
                    "EXTRACT(MONTH FROM mr.reading_date) AS reading_month, " +
                    "SUM(mr.difference) AS monthly_copies, " +
                    "SUM(mr.total_amount) AS monthly_revenue " +
                    "FROM meter_readings mr " +
                    "JOIN customers c ON mr.customer_id = c.id " +
                    "LEFT JOIN employees e ON c.responsible_employee_id = e.id " +
                    "WHERE EXTRACT(YEAR FROM mr.reading_date) = ? " +
                    "GROUP BY c.id, c.customer_code, c.company_name, e.first_name, e.last_name, reading_month " +
                    "ORDER BY c.company_name ASC";
            
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(query)) {
                ps.setInt(1, year);
                
                Map<Integer, JsonObject> customerMap = new LinkedHashMap<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int custId = rs.getInt("id");
                        JsonObject custObj = customerMap.get(custId);
                        if (custObj == null) {
                            custObj = new JsonObject();
                            custObj.addProperty("customerId", custId);
                            custObj.addProperty("customerCode", rs.getString("customer_code"));
                            custObj.addProperty("companyName", rs.getString("company_name"));
                            String empName = rs.getString("first_name");
                            custObj.addProperty("responsibleEmployee", empName != null ? empName + " " + rs.getString("last_name") : "Sorumlu Yok");
                            
                            // Initialize monthly buckets
                            JsonObject months = new JsonObject();
                            JsonObject revenue = new JsonObject();
                            for (int m = 1; m <= 12; m++) {
                                months.addProperty(String.valueOf(m), 0);
                                revenue.addProperty(String.valueOf(m), 0.0);
                            }
                            custObj.add("monthlyCopies", months);
                            custObj.add("monthlyRevenue", revenue);
                            custObj.addProperty("yearlyCopiesTotal", 0);
                            custObj.addProperty("yearlyRevenueTotal", 0.0);
                            
                            customerMap.put(custId, custObj);
                        }
                        
                        int month = rs.getInt("reading_month");
                        int monthlyCopies = rs.getInt("monthly_copies");
                        double monthlyRevenue = rs.getDouble("monthly_revenue");
                        
                        custObj.get("monthlyCopies").getAsJsonObject().addProperty(String.valueOf(month), monthlyCopies);
                        custObj.get("monthlyRevenue").getAsJsonObject().addProperty(String.valueOf(month), monthlyRevenue);
                        
                        custObj.addProperty("yearlyCopiesTotal", custObj.get("yearlyCopiesTotal").getAsInt() + monthlyCopies);
                        custObj.addProperty("yearlyRevenueTotal", custObj.get("yearlyRevenueTotal").getAsDouble() + monthlyRevenue);
                    }
                }
                
                for (JsonObject item : customerMap.values()) {
                    arr.add(item);
                }
                return successResponse(arr);
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        // Dynamic PDF Report Wizard Endpoint
        post("/api/reports/export-pdf", (req, res) -> {
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            int year = body.has("year") ? body.get("year").getAsInt() : 2026;
            
            Integer employeeId = null;
            if (body.has("employeeId") && !body.get("employeeId").isJsonNull()) {
                String empStr = body.get("employeeId").getAsString();
                if (!empStr.isEmpty() && !"Tümü".equalsIgnoreCase(empStr) && !"All".equalsIgnoreCase(empStr)) {
                    try {
                        employeeId = Integer.parseInt(empStr);
                    } catch (NumberFormatException e) {}
                }
            }
            
            boolean includeOverview = body.has("includeOverview") ? body.get("includeOverview").getAsBoolean() : true;
            boolean includeHandovers = body.has("includeHandovers") ? body.get("includeHandovers").getAsBoolean() : true;
            boolean includeMachines = body.has("includeMachines") ? body.get("includeMachines").getAsBoolean() : true;

            java.io.File tempFile = java.io.File.createTempFile("bormak_rapor_", ".pdf");
            try (Connection conn = getConnection()) {
                PdfReportService service = new PdfReportService();
                service.generateReport(tempFile.getAbsolutePath(), year, employeeId, 
                                       includeOverview, includeHandovers, includeMachines, conn);
                
                res.header("Content-Disposition", "attachment; filename=\"Bormak_Performans_Raporu.pdf\"");
                res.type("application/pdf");

                try (java.io.FileInputStream in = new java.io.FileInputStream(tempFile);
                     java.io.OutputStream out = res.raw().getOutputStream()) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                }
                return res.raw();
            } catch (Exception e) {
                e.printStackTrace();
                return errorResponse(res, 500, e.getMessage());
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            }
        });

        // Toner Types Endpoint (Toner Cost calculations)
        get("/api/toner-types", (req, res) -> {
            res.type("application/json");
            JsonArray arr = new JsonArray();
            try (Connection conn = getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT id, toner_name, unit_kg_cost, standard_page_capacity FROM toner_types ORDER BY toner_name ASC")) {
                while (rs.next()) {
                    JsonObject t = new JsonObject();
                    t.addProperty("id", rs.getInt("id"));
                    t.addProperty("tonerName", rs.getString("toner_name"));
                    t.addProperty("unitKgCost", rs.getDouble("unit_kg_cost"));
                    t.addProperty("standardPageCapacity", rs.getInt("standard_page_capacity"));
                    
                    double costPerGram = rs.getDouble("unit_kg_cost") / 1000.0;
                    double pagesPerGram = rs.getInt("standard_page_capacity") / 1000.0;
                    t.addProperty("costPerGram", costPerGram);
                    t.addProperty("pagesPerGram", pagesPerGram);
                    
                    arr.add(t);
                }
                return successResponse(arr);
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        post("/api/toner-types", (req, res) -> {
            res.type("application/json");
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            String name = body.get("tonerName").getAsString();
            double unitKgCost = body.get("unitKgCost").getAsDouble();
            int pageCapacity = body.get("standardPageCapacity").getAsInt();

            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("INSERT INTO toner_types (toner_name, unit_kg_cost, standard_page_capacity) VALUES (?, ?, ?)")) {
                ps.setString(1, name);
                ps.setDouble(2, unitKgCost);
                ps.setInt(3, pageCapacity);
                ps.executeUpdate();
                return successResponse("Toner türü kaydedildi.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        put("/api/toner-types/:id", (req, res) -> {
            res.type("application/json");
            int id = Integer.parseInt(req.params(":id"));
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            String name = body.get("tonerName").getAsString();
            double unitKgCost = body.get("unitKgCost").getAsDouble();
            int pageCapacity = body.get("standardPageCapacity").getAsInt();

            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("UPDATE toner_types SET toner_name = ?, unit_kg_cost = ?, standard_page_capacity = ? WHERE id = ?")) {
                ps.setString(1, name);
                ps.setDouble(2, unitKgCost);
                ps.setInt(3, pageCapacity);
                ps.setInt(4, id);
                if (ps.executeUpdate() > 0) {
                    return successResponse("Toner türü güncellendi.");
                }
                return errorResponse(res, 404, "Toner türü bulunamadı.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        delete("/api/toner-types/:id", (req, res) -> {
            res.type("application/json");
            int id = Integer.parseInt(req.params(":id"));
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM toner_types WHERE id = ?")) {
                ps.setInt(1, id);
                if (ps.executeUpdate() > 0) {
                    return successResponse("Toner türü silindi.");
                }
                return errorResponse(res, 404, "Toner türü bulunamadı.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        // Toner Fillings history (Toner Maliyet Analizi)
        get("/api/toner-fillings", (req, res) -> {
            res.type("application/json");
            JsonArray arr = new JsonArray();
            String query = "SELECT f.id, f.filled_grams, f.calculated_cost, f.expected_pages, f.filling_date, tt.toner_name " +
                    "FROM toner_fillings f JOIN toner_types tt ON f.toner_type_id = tt.id ORDER BY f.filling_date DESC";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(query);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject f = new JsonObject();
                    f.addProperty("id", rs.getInt("id"));
                    f.addProperty("tonerName", rs.getString("toner_name"));
                    f.addProperty("grams", rs.getInt("filled_grams"));
                    f.addProperty("cost", rs.getDouble("calculated_cost"));
                    f.addProperty("expectedPages", rs.getInt("expected_pages"));
                    f.addProperty("date", rs.getTimestamp("filling_date").toString());
                    arr.add(f);
                }
                return successResponse(arr);
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        post("/api/toner-fillings", (req, res) -> {
            res.type("application/json");
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            int tonerTypeId = body.get("tonerTypeId").getAsInt();
            int grams = body.get("grams").getAsInt();

            try (Connection conn = getConnection()) {
                double unitKgCost = 0.0;
                int standardPageCapacity = 20000;
                try (PreparedStatement check = conn.prepareStatement("SELECT unit_kg_cost, standard_page_capacity FROM toner_types WHERE id = ?")) {
                    check.setInt(1, tonerTypeId);
                    try (ResultSet rs = check.executeQuery()) {
                        if (rs.next()) {
                            unitKgCost = rs.getDouble("unit_kg_cost");
                            standardPageCapacity = rs.getInt("standard_page_capacity");
                        }
                    }
                }

                double cost = (unitKgCost / 1000.0) * grams;
                int pages = (int) ((standardPageCapacity / 1000.0) * grams);

                String query = "INSERT INTO toner_fillings (toner_type_id, filled_grams, calculated_cost, expected_pages) VALUES (?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(query)) {
                    ps.setInt(1, tonerTypeId);
                    ps.setInt(2, grams);
                    ps.setDouble(3, cost);
                    ps.setInt(4, pages);
                    ps.executeUpdate();
                }
                return successResponse("Toner dolum kaydı oluşturuldu.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        delete("/api/toner-fillings/:id", (req, res) -> {
            res.type("application/json");
            int id = Integer.parseInt(req.params(":id"));
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM toner_fillings WHERE id = ?")) {
                ps.setInt(1, id);
                if (ps.executeUpdate() > 0) {
                    return successResponse("Toner dolum kaydı silindi.");
                }
                return errorResponse(res, 404, "Toner dolum kaydı bulunamadı.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        // Device and Contract Registration
        get("/api/contracts/devices", (req, res) -> {
            res.type("application/json");
            JsonArray arr = new JsonArray();
            String query = "SELECT d.device_id, d.serial_number, d.brand_model, d.customer_id, d.ownership_type, d.business_model, c.company_name " +
                    "FROM devices d LEFT JOIN customers c ON d.customer_id = c.id " +
                    "ORDER BY d.device_id DESC";
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement(query);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject d = new JsonObject();
                    d.addProperty("id", rs.getInt("device_id"));
                    d.addProperty("serialNumber", rs.getString("serial_number"));
                    d.addProperty("brandModel", rs.getString("brand_model"));
                    d.addProperty("customerId", rs.getInt("customer_id"));
                    
                    String company = rs.getString("company_name");
                    d.addProperty("companyName", company != null ? company : "Müşteri Atanmamış");
                    
                    String own = rs.getString("ownership_type");
                    d.addProperty("ownershipType", own);
                    d.addProperty("ownershipTypeDisplay", "MÜŞTERİNİN_MAKİNESİ".equals(own) ? "Müşterinin Makinesi" : "Bizim Makinemiz");
                    
                    String bm = rs.getString("business_model");
                    d.addProperty("businessModel", bm);
                    
                    String bmDisp = "Kopya Başı Sayaç Sistemi";
                    if ("MALZEME_KARSILIGI".equals(bm)) bmDisp = "Malzeme Karşılığı Sistemi";
                    else if ("NORMAL".equals(bm)) bmDisp = "Normal Sistem";
                    d.addProperty("businessModelDisplay", bmDisp);
                    
                    arr.add(d);
                }
                return successResponse(arr);
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        post("/api/contracts/devices", (req, res) -> {
            res.type("application/json");
            JsonObject body = gson.fromJson(req.body(), JsonObject.class);
            String serial = body.get("serialNumber").getAsString();
            String brandModel = body.get("brandModel").getAsString();
            int customerId = body.get("customerId").getAsInt();
            String ownershipType = body.get("ownershipType").getAsString();
            String businessModel = body.get("businessModel").getAsString();

            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("INSERT INTO devices (serial_number, brand_model, customer_id, ownership_type, business_model) VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, serial);
                ps.setString(2, brandModel);
                ps.setInt(3, customerId);
                ps.setString(4, ownershipType);
                ps.setString(5, businessModel);
                ps.executeUpdate();
                return successResponse("Sözleşme cihazı kaydedildi.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        delete("/api/contracts/devices/:id", (req, res) -> {
            res.type("application/json");
            int id = Integer.parseInt(req.params(":id"));
            try (Connection conn = getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM devices WHERE device_id = ?")) {
                ps.setInt(1, id);
                if (ps.executeUpdate() > 0) {
                    return successResponse("Sözleşme cihazı silindi.");
                }
                return errorResponse(res, 404, "Cihaz bulunamadı.");
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        // Contract reporting data stats
        get("/api/contracts/reporting", (req, res) -> {
            res.type("application/json");
            JsonObject summary = new JsonObject();
            try (Connection conn = getConnection()) {
                // Costs
                summary.addProperty("kbTonerCost", fetchTonerCostByBM(conn, "KOPYA_BASI"));
                summary.addProperty("mkTonerCost", fetchTonerCostByBM(conn, "MALZEME_KARSILIGI"));
                summary.addProperty("mkMaterialCost", fetchMaterialCostByBM(conn, "MALZEME_KARSILIGI"));
                summary.addProperty("kbMaterialCost", fetchMaterialCostByBM(conn, "KOPYA_BASI"));
                summary.addProperty("sozlesmesizRevenue", fetchBilledAmountByBM(conn, "NORMAL"));

                // Distribution of devices
                JsonArray dist = new JsonArray();
                String queryDist = "SELECT c.business_model, cm.ownership_type, COUNT(*) AS count " +
                        "FROM customer_machines cm JOIN customers c ON cm.customer_id = c.id " +
                        "GROUP BY c.business_model, cm.ownership_type";
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(queryDist)) {
                    while (rs.next()) {
                        JsonObject d = new JsonObject();
                        String bm = rs.getString("business_model");
                        String ot = rs.getString("ownership_type");
                        
                        String bmDisp = "Kopya Başı";
                        if ("MALZEME_KARSILIGI".equals(bm)) bmDisp = "Malzeme Karşılığı";
                        else if ("NORMAL".equals(bm)) bmDisp = "Normal Sistem";
                        
                        d.addProperty("businessModel", bmDisp);
                        d.addProperty("ownershipType", "MÜŞTERİNİN_MAKİNESİ".equals(ot) ? "Müşterinin Makinesi" : "Bizim Makinemiz");
                        d.addProperty("count", rs.getInt("count"));
                        dist.add(d);
                    }
                }
                summary.add("distributions", dist);

                // History
                JsonArray hist = new JsonArray();
                String queryHist = "SELECT ol.id, ol.operation_date, ol.operation_type, ol.material_description, ol.calculated_cost, ol.billed_amount, ol.expected_pages, " +
                        "cm.serial_number, cm.machine_name AS brand_model " +
                        "FROM operations_log ol JOIN customer_machines cm ON ol.device_id = cm.id " +
                        "ORDER BY ol.operation_date DESC";
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(queryHist)) {
                    while (rs.next()) {
                        JsonObject h = new JsonObject();
                        h.addProperty("id", rs.getInt("id"));
                        h.addProperty("date", rs.getTimestamp("operation_date").toString());
                        h.addProperty("machine", rs.getString("brand_model") + " (" + rs.getString("serial_number") + ")");
                        
                        String rawType = rs.getString("operation_type");
                        String dispType = rawType;
                        if ("KOPYA_BASI_TONER".equals(rawType)) dispType = "Toner (Kopya Başı)";
                        else if ("MALZEME_KARSILIGI_TONER".equals(rawType)) dispType = "Toner (Malzeme Karşılığı)";
                        else if ("NORMAL_TONER".equals(rawType)) dispType = "Toner (Satış)";
                        else if ("KOPYA_BASI_YEDEK_PARCA".equals(rawType)) dispType = "Yedek Parça (Kopya Başı)";
                        else if ("MALZEME_KARSILIGI_YEDEK_PARCA".equals(rawType)) dispType = "Yedek Parça (Malzeme Karşılığı)";
                        else if ("NORMAL_YEDEK_PARCA".equals(rawType)) dispType = "Yedek Parça (Satış)";
                        
                        h.addProperty("operationType", dispType);
                        h.addProperty("desc", rs.getString("material_description"));
                        h.addProperty("billed", rs.getDouble("billed_amount"));
                        h.addProperty("cost", rs.getDouble("calculated_cost"));
                        h.addProperty("expectedPages", rs.getInt("expected_pages") > 0 ? rs.getInt("expected_pages") + " Sayfa" : "-");
                        hist.add(h);
                    }
                }
                summary.add("operationsLog", hist);

                return successResponse(summary);
            } catch (Exception e) {
                return errorResponse(res, 500, e.getMessage());
            }
        });

        // Root redirects to index.html
        get("/", (req, res) -> {
            res.redirect("/index.html");
            return null;
        });

        System.out.println("Bormak Web Server is running on port " + PORT + "...");
    }

    // ----------------------------------------------------
    // DATABASE HELPER METHODS
    // ----------------------------------------------------

    private static synchronized void initializeDataSource() {
        if (dataSource != null) return;
        try {
            String dbUrl = System.getenv("DATABASE_URL");
            HikariConfig config = new HikariConfig();
            
            if (dbUrl != null && !dbUrl.isEmpty()) {
                System.out.println("Using environment DATABASE_URL for database connection...");
                String username = null;
                String password = null;
                String jdbcUrl = dbUrl;
                
                if (dbUrl.startsWith("postgres://") || dbUrl.startsWith("postgresql://")) {
                    try {
                        String cleanUrl = dbUrl.substring(dbUrl.indexOf("://") + 3);
                        int atIndex = cleanUrl.indexOf("@");
                        if (atIndex != -1) {
                            String userInfo = cleanUrl.substring(0, atIndex);
                            String hostInfo = cleanUrl.substring(atIndex + 1);
                            
                            int colonIndex = userInfo.indexOf(":");
                            if (colonIndex != -1) {
                                username = userInfo.substring(0, colonIndex);
                                password = userInfo.substring(colonIndex + 1);
                            } else {
                                username = userInfo;
                            }
                            jdbcUrl = "jdbc:postgresql://" + hostInfo;
                        } else {
                            jdbcUrl = "jdbc:postgresql://" + cleanUrl;
                        }
                    } catch (Exception e) {
                        System.err.println("Failed to parse DATABASE_URL: " + e.getMessage());
                    }
                }
                
                Class.forName("org.postgresql.Driver");
                config.setJdbcUrl(jdbcUrl);
                if (username != null) {
                    config.setUsername(username);
                }
                if (password != null) {
                    config.setPassword(password);
                }
                
                // Connection pool settings
                config.setMaximumPoolSize(15);
                config.setMinimumIdle(3);
                config.setIdleTimeout(30000);
                config.setConnectionTimeout(10000);
                config.setMaxLifetime(1800000);
                
                dataSource = new HikariDataSource(config);
                System.out.println("HikariCP PostgreSQL connection pool initialized successfully.");
            } else {
                System.out.println("Using local MySQL fallback connection...");
                Class.forName("com.mysql.cj.jdbc.Driver");
                config.setJdbcUrl("jdbc:mysql://localhost:3306/stok_takip?useSSL=false&serverTimezone=Europe/Istanbul&allowPublicKeyRetrieval=true");
                config.setUsername("root");
                config.setPassword("XPnbaGXC19751980");
                
                // Connection pool settings
                config.setMaximumPoolSize(15);
                config.setMinimumIdle(3);
                config.setIdleTimeout(30000);
                config.setConnectionTimeout(10000);
                config.setMaxLifetime(1800000);
                
                dataSource = new HikariDataSource(config);
                System.out.println("HikariCP MySQL connection pool initialized successfully.");
            }
        } catch (Exception e) {
            System.err.println("HikariCP initialization failed: " + e.getMessage());
            throw new RuntimeException("Database pool initialization failed", e);
        }
    }

    private static Connection getConnection() throws Exception {
        if (dataSource == null) {
            initializeDataSource();
        }
        return dataSource.getConnection();
    }

    // JSON response wrappers
    private static String successResponse(Object data) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "success");
        response.add("data", gson.toJsonTree(data));
        return gson.toJson(response);
    }

    private static String successResponse(String message) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "success");
        response.addProperty("message", message);
        return gson.toJson(response);
    }

    private static String errorResponse(spark.Response res, int statusCode, String message) {
        res.status(statusCode);
        JsonObject response = new JsonObject();
        response.addProperty("status", "error");
        response.addProperty("message", message);
        return gson.toJson(response);
    }

    // Helper statistics queries
    private static double fetchTonerCostByBM(Connection conn, String businessModel) throws SQLException {
        String query = "SELECT SUM(ol.calculated_cost) AS total FROM operations_log ol " +
                "JOIN customer_machines cm ON ol.device_id = cm.id " +
                "JOIN customers c ON cm.customer_id = c.id " +
                "WHERE c.business_model = ? AND (ol.operation_type = 'KOPYA_BASI' OR ol.operation_type LIKE '%TONER')";
        try (PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, businessModel);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble("total");
            }
        }
        return 0.0;
    }

    private static double fetchMaterialCostByBM(Connection conn, String businessModel) throws SQLException {
        String query = "SELECT SUM(ol.calculated_cost) AS total FROM operations_log ol " +
                "JOIN customer_machines cm ON ol.device_id = cm.id " +
                "JOIN customers c ON cm.customer_id = c.id " +
                "WHERE c.business_model = ? AND (ol.operation_type = 'MALZEME_KARSILIGI' OR ol.operation_type LIKE '%PARCA')";
        try (PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, businessModel);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble("total");
            }
        }
        return 0.0;
    }

    private static double fetchBilledAmountByBM(Connection conn, String businessModel) throws SQLException {
        String query = "SELECT SUM(ol.billed_amount) AS total FROM operations_log ol " +
                "JOIN customer_machines cm ON ol.device_id = cm.id " +
                "JOIN customers c ON cm.customer_id = c.id " +
                "WHERE c.business_model = ?";
        try (PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setString(1, businessModel);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getDouble("total");
            }
        }
        return 0.0;
    }
}
