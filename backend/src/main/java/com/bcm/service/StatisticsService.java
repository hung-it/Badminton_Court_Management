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

@Service
@RequiredArgsConstructor
@Transactional(readOnly=true, isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class StatisticsService {
    private final InvoiceRepository invoices;
    private final ImportOrderRepository imports;

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
}
