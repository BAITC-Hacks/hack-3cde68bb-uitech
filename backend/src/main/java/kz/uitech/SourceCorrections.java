package kz.uitech;

import static kz.uitech.Model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** A correction creates an immutable child dataset, retaining source rows and binary references. */
@Service
public class SourceCorrections {
    private final LocalStore store;
    private final ObjectMapper mapper;
    public SourceCorrections(LocalStore store,ObjectMapper mapper){this.store=store;this.mapper=mapper;}

    public ObjectNode correct(String datasetId,String productId,ProductCorrection change)throws IOException {
        Validation.require(change!=null,"Исправление обязательно");
        Validation.text(change.author(),"Автор");Validation.text(change.reason(),"Причина и источник проверки");
        Validation.require(change.author().length()<=200&&change.reason().length()<=2000,"Слишком длинный автор или причина");
        Validation.require(change.purchase()!=null||change.stock()!=null||change.supplierPolicy()!=null,"Выберите данные для исправления");
        Dataset d=store.dataset(datasetId);
        Product old=d.products().stream().filter(p->p.productId().equals(productId)).findFirst().orElseThrow(()->new ApiException(404,"NOT_FOUND","Товар не найден в наборе"));
        String sourceId="manual_"+UUID.randomUUID().toString().replace("-","");
        Ref ref=new Ref(sourceId,null,null);
        Product updated=old;
        if(change.purchase()!=null){
            PurchaseFacts p=change.purchase();Validation.text(p.purchaseUnit(),"Единица закупки");
            if(p.purchaseUnitFactor()!=null)Validation.number(p.purchaseUnitFactor(),"Коэффициент единиц",false);
            if(p.moqPurchaseQty()!=null)Validation.number(p.moqPurchaseQty(),"MOQ",true);
            if(p.packMultiplePurchaseQty()!=null)Validation.number(p.packMultiplePurchaseQty(),"Кратность",false);
            List<Ref> refs=new ArrayList<>(old.sourceRefs());refs.add(ref);
            updated=new Product(old.productId(),old.supplierId(),old.sku1c(),old.supplierArticle(),old.name(),old.stockUnit(),p.purchaseUnit().trim(),p.purchaseUnitFactor(),
                    p.categoryId()==null||p.categoryId().isBlank()?null:p.categoryId().trim(),p.moqPurchaseQty(),p.packMultiplePurchaseQty(),refs);
        }
        List<Inventory> stocks=new ArrayList<>(d.inventory());Inventory beforeStock=null,afterStock=null;
        if(change.stock()!=null){
            StockFacts s=change.stock();Validation.text(s.warehouseId(),"Склад");Validation.require(s.asOf()!=null,"Дата остатка обязательна");Validation.number(s.freeStockQty(),"Свободный остаток",true);
            boolean known=d.salesCoverage().stream().anyMatch(c->c.warehouseId().equals(s.warehouseId()))||d.inventory().stream().anyMatch(i->i.warehouseId().equals(s.warehouseId()));
            Validation.require(known,"Выберите склад, присутствующий в наборе");
            beforeStock=stocks.stream().filter(i->i.productId().equals(productId)&&i.warehouseId().equals(s.warehouseId())&&i.asOf().equals(s.asOf())).findFirst().orElse(null);
            List<Ref> refs=new ArrayList<>(beforeStock==null?List.of():beforeStock.sourceRefs());refs.add(ref);
            afterStock=new Inventory(productId,s.warehouseId(),s.asOf(),s.freeStockQty(),refs);
            stocks.removeIf(i->i.productId().equals(productId)&&i.warehouseId().equals(s.warehouseId())&&i.asOf().equals(s.asOf()));stocks.add(afterStock);
        }
        SupplierPolicy policy=change.supplierPolicy();
        if(policy!=null){
            Validation.require(old.supplierId().equals(policy.supplierId()),"Политика должна относиться к поставщику выбранного товара");
            Validation.require(policy.leadTimeDays()!=null&&policy.leadTimeDays()>=0&&policy.reviewPeriodDays()!=null&&policy.reviewPeriodDays()>0&&(long)policy.leadTimeDays()+policy.reviewPeriodDays()<=366,"Горизонт поставки и пересмотра должен быть 1–366 дней");
        }
        var audit=new CorrectionAudit(datasetId,productId,change.author().trim(),change.reason().trim(),Instant.now().toString(),old,updated,beforeStock,afterStock,policy);
        List<Source> sources=new ArrayList<>(d.sources());sources.add(new Source(sourceId,"manual","product_correction",null,
                "Исправление товара "+old.sku1c()+". "+change.author().trim()+": "+change.reason().trim(),audit));
        List<Product> products=new ArrayList<>(d.products());products.set(products.indexOf(old),updated);
        // Keep unresolved issues, coverage, availability and reported quantities unchanged.
        Dataset child=new Dataset(d.schemaVersion(),d.name()+" · уточнение "+old.sku1c(),d.dataKind(),d.timezone(),d.suppliers(),products,d.salesCoverage(),d.sales(),stocks,d.inbound(),d.inboundCoverage(),
                d.availability(),d.seasonalityProfiles(),sources,d.issues(),d.monthlySales(),d.monthlyStock(),d.reportedInbound(),d.reportedPurchaseRules());
        return store.putCorrectedDataset(child,datasetId);
    }
}
