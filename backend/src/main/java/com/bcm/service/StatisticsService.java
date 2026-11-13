package com.bcm.service;

import com.bcm.dto.response.StatisticsResponse;
import com.bcm.exception.BadRequestException;
import com.bcm.repository.ImportOrderRepository;
import com.bcm.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class StatisticsService {
    private final InvoiceRepository invoices;
    private final ImportOrderRepository imports;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final RevenueBreakdownService breakdown;

    public StatisticsResponse report(String period, int year, Integer fromYear, Integer toYear) {
        String mode=period.toUpperCase(Locale.ROOT);
        if(!java.util.Set.of("MONTH","QUARTER","YEAR","YEARS").contains(mode))
            throw new BadRequestException("Kỳ thống kê phải là MONTH, QUARTER, YEAR hoặc YEARS");
        int first=mode.equals("YEARS") && fromYear!=null ? fromYear : year;
        int last=mode.equals("YEARS") && toYear!=null ? toYear : year;
        if(first<1 || last>9998 || first>last || last-first>49)
            throw new BadRequestException("Chọn khoảng năm hợp lệ, tối đa 50 năm (1–9998)");
        var rows=new ArrayList<StatisticsResponse.Row>();
        BigDecimal revenue=BigDecimal.ZERO,expense=BigDecimal.ZERO;
        int step=mode.equals("MONTH")?1:mode.equals("QUARTER")?3:12;
        for(int y=first;y<=last;y++) {
            for(int month=1;month<=12;month+=step) {
                LocalDate from=LocalDate.of(y,month,1),end=from.plusMonths(step);
                BigDecimal thu=invoices.sumRevenueByPeriod("PAID",from.atStartOfDay(),end.atStartOfDay()).getTotalAmount();
                BigDecimal chi=imports.sumExpense(from,end);
                if(thu==null)thu=BigDecimal.ZERO;
                if(chi==null)chi=BigDecimal.ZERO;
                String label=mode.equals("MONTH")?"Tháng "+month+"/"+y:mode.equals("QUARTER")?"Quý "+((month-1)/3+1)+"/"+y:"Năm "+y;
                rows.add(new StatisticsResponse.Row(label,from,end.minusDays(1),thu,chi,thu.subtract(chi)));
                revenue=revenue.add(thu);expense=expense.add(chi);
            }
        }
        return new StatisticsResponse(mode,rows,revenue,expense,revenue.subtract(expense));
    }

    public record MatrixRow(String id,String label,List<BigDecimal> values,BigDecimal total) {}
    public record Matrix(String type,List<String> columns,List<MatrixRow> rows,List<BigDecimal> totals,BigDecimal total,String note) {}
    public Matrix matrix(String type,String period,int year,int first,int last,LocalDate from,LocalDate to,String search) {
        if(!java.util.Set.of("DATE","TIME","IMPORT","STAFF","CUSTOMER","PRODUCT","COURT").contains(type))throw new BadRequestException("Invalid report type");
        if(!java.util.Set.of("DATE","MONTH","QUARTER","YEAR","YEARS").contains(period)||!period.equals("DATE")&&(year<1||year>9998||first<1||last>9998||first>last||last-first>49))throw new BadRequestException("Invalid period/year range");
        if(from!=null&&to!=null&&from.isAfter(to))throw new BadRequestException("Từ ngày không được sau Đến ngày");
        var cols=new ArrayList<String>();var starts=new ArrayList<LocalDate>();var ends=new ArrayList<LocalDate>();
        if(type.equals("DATE")||period.equals("DATE")){
            String source=type.equals("IMPORT")?"select min(import_date) as first,max(import_date) as last from import_orders where status='RECEIVED'":"select min(created_at)::date as first,max(created_at)::date as last from invoices where status='PAID' and deleted_at is null";
            var bounds=jdbc.queryForMap(source);
            LocalDate lo=from!=null?from:bounds.get("first")==null?to:((java.sql.Date)bounds.get("first")).toLocalDate();
            LocalDate hi=to!=null?to:bounds.get("last")==null?from:((java.sql.Date)bounds.get("last")).toLocalDate();
            if(lo!=null&&hi!=null){
                if(lo.isAfter(hi)){if(from!=null)hi=lo;else lo=hi;}
                for(LocalDate d=lo;!d.isAfter(hi);d=d.plusDays(1)){
                    cols.add(d.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy")));starts.add(d);ends.add(d.plusDays(1));
                }
            }
        }else{
            boolean years=period.equals("YEARS");int begin=years?first:year,end=years?last:year,step=years||period.equals("YEAR")?12:period.equals("QUARTER")?3:1;
            for(int y=begin;y<=end;y++)for(int m=1;m<=12;m+=step){LocalDate d=LocalDate.of(y,m,1);cols.add(step==12?""+y:step==3?"Q"+((m-1)/3+1)+"/"+y:"T"+m+"/"+y);starts.add(d);ends.add(d.plusMonths(step));}
        }
        var data=new java.util.LinkedHashMap<String,List<BigDecimal>>();var labels=new java.util.HashMap<String,String>();
        if(type.equals("TIME"))for(String k:List.of("Tổng thu","Chi phí nhập","Chênh lệch")){data.put(k,new ArrayList<>(java.util.Collections.nCopies(cols.size(),BigDecimal.ZERO)));labels.put(k,k);}
        String code=search==null?"":search.strip();
        for(int i=0;i<starts.size();i++){
            LocalDate lo=starts.get(i),hi=ends.get(i);if(from!=null&&from.isAfter(lo))lo=from;if(to!=null&&to.plusDays(1).isBefore(hi))hi=to.plusDays(1);if(!lo.isBefore(hi))continue;
            if(type.equals("TIME")){
                BigDecimal revenue=invoices.sumRevenueByPeriod("PAID",lo.atStartOfDay(),hi.atStartOfDay()).getTotalAmount();if(revenue==null)revenue=BigDecimal.ZERO;
                BigDecimal expense=imports.sumExpense(lo,hi);if(expense==null)expense=BigDecimal.ZERO;
                data.get("Tổng thu").set(i,revenue);data.get("Chi phí nhập").set(i,expense);data.get("Chênh lệch").set(i,revenue.subtract(expense));continue;
            }
            if(type.equals("PRODUCT")||type.equals("COURT")){
                for(var row:breakdown.allocationValues(lo.atStartOfDay(),hi.atStartOfDay(),type.equals("COURT"))){
                    String key=row.id()==null?"UNASSIGNED":row.id().toString();labels.put(key,row.label()==null||row.label().isBlank()?"Chưa có tên":row.label());
                    var v=data.computeIfAbsent(key,k->new ArrayList<>(java.util.Collections.nCopies(cols.size(),BigDecimal.ZERO)));v.set(i,v.get(i).add(row.amount()));
                }continue;
            }
            String query;var args=new ArrayList<Object>();
            if(type.equals("IMPORT")){
                query="select o.id::text as key,o.import_date as import_date,coalesce(nullif(s.name,''),'Chưa có tên nhà cung cấp') as label,sum(o.total_amount) as amount from import_orders o left join suppliers s on s.id=o.supplier_id where o.status='RECEIVED' and o.import_date>=? and o.import_date<? and (?='' or o.id::text=?) group by o.id,s.name";
                args.add(lo);args.add(hi);
            }else{
                String key=type.equals("STAFF")?"i.created_by":type.equals("CUSTOMER")?"i.customer_id":"i.id";
                String label=type.equals("STAFF")?"coalesce(nullif(s.full_name,''),'Chưa có tên nhân viên')":"coalesce(nullif(c.full_name,''),'Chưa có tên khách hàng')";
                query="select coalesce("+key+"::text,'UNASSIGNED') as key,"+label+" as label,sum(i.total_amount) as amount from invoices i left join staffs s on s.id=i.created_by left join customers c on c.id=i.customer_id where i.status='PAID' and i.deleted_at is null and i.created_at>=? and i.created_at<? and (?='' or "+key+"::text=?) group by "+key+","+label;
                args.add(lo.atStartOfDay());args.add(hi.atStartOfDay());
            }
            args.add(code);args.add(code);
            for(var row:jdbc.queryForList(query,args.toArray())){String key=(String)row.get("key");labels.put(key,type.equals("IMPORT")?"Phiếu nhập · "+row.get("label")+" · "+row.get("import_date"): (String)row.get("label"));var v=data.computeIfAbsent(key,k->new ArrayList<>(java.util.Collections.nCopies(cols.size(),BigDecimal.ZERO)));v.set(i,(BigDecimal)row.get("amount"));}
        }
        var rows=new ArrayList<MatrixRow>();var totals=new ArrayList<>(java.util.Collections.nCopies(cols.size(),BigDecimal.ZERO));
        for(var e:data.entrySet()){BigDecimal total=e.getValue().stream().reduce(BigDecimal.ZERO,BigDecimal::add);rows.add(new MatrixRow(e.getKey(),labels.get(e.getKey()),e.getValue(),total));if(!type.equals("TIME")||e.getKey().equals("Tổng thu"))for(int i=0;i<totals.size();i++)totals.set(i,totals.get(i).add(e.getValue().get(i)));}
        rows.sort(java.util.Comparator.comparing(MatrixRow::label).thenComparing(MatrixRow::id));
        return new Matrix(type,cols,rows,totals,totals.stream().reduce(BigDecimal.ZERO,BigDecimal::add),type.equals("COURT")?"Tiền sân sau phân bổ giảm giá, không gồm sản phẩm":type.equals("PRODUCT")?"Giá trị sản phẩm sau phân bổ giảm giá, không gồm tiền sân":"Chỉ hóa đơn PAID chưa xóa và phiếu nhập RECEIVED");
    }

    public byte[] excel(Matrix m,String filters) {
        try(var bytes=new java.io.ByteArrayOutputStream();var zip=new java.util.zip.ZipOutputStream(bytes)){
            var sheet=new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            java.util.function.Function<String,String> cell=v->"<c t=\"inlineStr\"><is><t>"+v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")+"</t></is></c>";
            sheet.append("<row>").append(cell.apply("BCM - "+switch(m.type()){case "IMPORT"->"Phiếu nhập hàng";case "STAFF"->"Nhân viên";case "CUSTOMER"->"Khách hàng";case "PRODUCT"->"Sản phẩm";case "COURT"->"Tiền sân";default->"Hóa đơn";})).append("</row><row>").append(cell.apply(filters)).append("</row><row>").append(cell.apply(m.note())).append("</row><row>").append(cell.apply("Chỉ tiêu"));
            for(String c:m.columns())sheet.append(cell.apply(c));sheet.append(cell.apply("Tổng cộng (VND)")).append("</row>");
            var all=new ArrayList<>(m.rows());all.add(new MatrixRow("TOTAL","Tổng cộng",m.totals(),m.total()));
            for(var r:all){sheet.append("<row>").append(cell.apply(r.label()));var values=new ArrayList<>(r.values());values.add(r.total());for(var v:values)sheet.append("<c s=\"1\"><v>").append(v.toPlainString()).append("</v></c>");sheet.append("</row>");}
            sheet.append("</sheetData></worksheet>");
            var entries=java.util.Map.of(
              "[Content_Types].xml","<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>",
              "_rels/.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>",
              "xl/workbook.xml","<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"BCM\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
              "xl/_rels/workbook.xml.rels","<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>",
              "xl/styles.xml","<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts><fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills><borders count=\"1\"><border/></borders><cellStyleXfs count=\"1\"><xf/></cellStyleXfs><cellXfs count=\"2\"><xf/><xf numFmtId=\"3\" applyNumberFormat=\"1\"/></cellXfs></styleSheet>",
              "xl/worksheets/sheet1.xml",sheet.toString());
            for(var e:entries.entrySet()){zip.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));zip.write(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}
            zip.finish();return bytes.toByteArray();
        }catch(java.io.IOException ex){throw new IllegalStateException("Excel export failed",ex);}
    }
}
