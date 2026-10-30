package com.bcm.service;

import com.bcm.entity.Product;
import com.bcm.exception.*;
import com.bcm.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

@Service @RequiredArgsConstructor
@Transactional(readOnly=true)
public class ProductPriceService {
    private static final UUID IMPORT_CATEGORY=UUID.nameUUIDFromBytes("bcm:manual-import-category".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private final ProductRepository products;
    private final CategoryRepository categories;
    public boolean managed(UUID categoryId) {
        return IMPORT_CATEGORY.equals(categoryId)||categories.findById(categoryId).map(c->"Hàng nhập kho".equalsIgnoreCase(c.getCategoryName().strip())).orElse(false);
    }
    public Product describe(Product p){p.setPriceApprovalManaged(managed(p.getCategoryId()));return p;}
    public void requireApproved(Product p){
        if(managed(p.getCategoryId())&&p.getPrice().signum()<=0)throw new BadRequestException("Sản phẩm nhập kho chưa được duyệt giá niêm yết");
    }
    public List<Product> sellable(){
        var ids=new HashSet<UUID>();ids.add(IMPORT_CATEGORY);
        categories.findAll().stream().filter(c->"Hàng nhập kho".equalsIgnoreCase(c.getCategoryName().strip())).forEach(c->ids.add(c.getId()));
        return products.findByDeletedAtIsNullOrderByNameAsc().stream().filter(p->!ids.contains(p.getCategoryId())||p.getPrice().signum()>0).toList();
    }
    @Transactional
    public Product approve(UUID id,BigDecimal price,int version){
        var product=products.findByIdForPriceApproval(id).filter(p->p.getDeletedAt()==null).orElseThrow(()->new ResourceNotFoundException("Không tìm thấy sản phẩm"));
        if(!managed(product.getCategoryId()))throw new BadRequestException("Chỉ duyệt giá cho danh mục Hàng nhập kho");
        if(!Objects.equals(version,product.getVersion()))throw new DuplicateResourceException("Giá hoặc tồn kho đã thay đổi; hãy tải lại sản phẩm");
        if(price.signum()<=0||price.scale()>2||price.compareTo(new BigDecimal("99999999.99"))>0)throw new BadRequestException("Giá niêm yết phải lớn hơn 0 và tối đa 99.999.999,99đ");
        product.setPrice(price);return describe(products.saveAndFlush(product));
    }
}
