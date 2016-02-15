package org.kuali.ole.oleng.service.impl;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.map.HashedMap;
import org.codehaus.jettison.json.JSONObject;
import org.kuali.ole.DocumentUniqueIDPrefix;
import org.kuali.ole.docstore.engine.service.storage.rdbms.pojo.BibRecord;
import org.kuali.ole.constants.OleNGConstants;
import org.kuali.ole.oleng.batch.process.model.ValueByPriority;
import org.kuali.ole.oleng.batch.profile.model.BatchProcessProfile;
import org.kuali.ole.oleng.batch.profile.model.BatchProfileDataMapping;
import org.kuali.ole.oleng.resolvers.*;
import org.kuali.ole.oleng.service.OrderImportService;
import org.kuali.ole.pojo.OleTxRecord;
import org.kuali.ole.spring.batch.BatchUtil;
import org.kuali.ole.utility.MarcRecordUtil;
import org.kuali.rice.krad.service.BusinessObjectService;
import org.kuali.rice.krad.service.KRADServiceLocator;
import org.marc4j.marc.Record;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Created by SheikS on 1/6/2016.
 */
public class OrderImportServiceImpl implements OrderImportService {

    private List<TxValueResolver> valueResolvers;
    private MarcRecordUtil marcRecordUtil;
    private BusinessObjectService businessObjectService;

    @Override
    public OleTxRecord processDataMapping(String bibId, BatchProcessProfile batchProcessProfile) {
        OleTxRecord oleTxRecord = new OleTxRecord();
        List<BatchProfileDataMapping> batchProfileDataMappingList = batchProcessProfile.getBatchProfileDataMappingList();

        if (CollectionUtils.isNotEmpty(batchProfileDataMappingList)) {

            BibRecord bibRecord = getBusinessObjectService().findBySinglePrimaryKey(BibRecord.class, DocumentUniqueIDPrefix.getDocumentId(bibId));
            List<Record> records = getMarcRecordUtil().convertMarcXmlContentToMarcRecord(bibRecord.getContent());
            Record marcRecord = records.get(0);

            BatchUtil batchUtil = new BatchUtil();
            batchUtil.sortDataMappings(batchProfileDataMappingList);
            Map<String, List<ValueByPriority>> valueByPriorityMap = new HashedMap();

            for (Iterator<BatchProfileDataMapping> iterator = batchProfileDataMappingList.iterator(); iterator.hasNext(); ) {
                BatchProfileDataMapping batchProfileDataMapping = iterator.next();
                String destinationField = batchProfileDataMapping.getField();
                boolean multiValue = batchProfileDataMapping.isMultiValue();
                List<String> fieldValues = batchUtil.getFieldValues(marcRecord, batchProfileDataMapping, multiValue);

                if (CollectionUtils.isNotEmpty(fieldValues)) {
                    int priority = batchProfileDataMapping.getPriority();

                    batchUtil.buildingValuesForDestinationBasedOnPriority(valueByPriorityMap, destinationField, multiValue, fieldValues, priority);
                }
            }

            for (Iterator<String> iterator = valueByPriorityMap.keySet().iterator(); iterator.hasNext(); ) {
                String destinationField =  iterator.next();
                for (Iterator<TxValueResolver> valueResolverIterator = getValueResolvers().iterator(); valueResolverIterator.hasNext(); ) {
                    TxValueResolver txValueResolver = valueResolverIterator.next();
                    if (txValueResolver.isInterested(destinationField)) {
                        String destinationValue = getDestinationValue(valueByPriorityMap,destinationField);
                        txValueResolver.setAttributeValue(oleTxRecord, destinationValue);
                    }
                }
            }
        }
        return oleTxRecord;
    }

    private String getDestinationValue(Map<String, List<ValueByPriority>> valueByPriorityMap, String fieldType) {

        List<ValueByPriority> vals = valueByPriorityMap.get(fieldType);
        for (Iterator<ValueByPriority> iterator1 = vals.iterator(); iterator1.hasNext(); ) {
            ValueByPriority valueByPriority = iterator1.next();
            List<String> values = valueByPriority.getValues();
            if (CollectionUtils.isNotEmpty(values)) {
                return values.get(0);
            }
        }

        return null;
    }

    public MarcRecordUtil getMarcRecordUtil() {
        if (null == marcRecordUtil) {
            marcRecordUtil = new MarcRecordUtil();
        }
        return marcRecordUtil;
    }

    public BusinessObjectService getBusinessObjectService() {
        if (null == businessObjectService) {
            businessObjectService = KRADServiceLocator.getBusinessObjectService();
        }
        return businessObjectService;
    }

    public List<TxValueResolver> getValueResolvers() {
        if (null == valueResolvers) {
            valueResolvers = new ArrayList<>();
            valueResolvers.add(new AccountNumberValueResolver());
            valueResolvers.add(new AssignToUserValueResolver());
            valueResolvers.add(new BuildingCodeValueResolver());
            valueResolvers.add(new CaptionValueResolver());
            valueResolvers.add(new ChartCodeValueResolver());
            valueResolvers.add(new ItemChartCodeValueResolver());
            valueResolvers.add(new QuantityValueResolver());
            valueResolvers.add(new ContractManagerValueResolver());
            valueResolvers.add(new CostSourceValueResolver());
            valueResolvers.add(new DefaultLocationValueResolver());
            valueResolvers.add(new DeliveryCampusCodeValueResolver());
            valueResolvers.add(new DiscountValueResolver());
            valueResolvers.add(new DonorCodeValueResolver());
            valueResolvers.add(new FundingSourceValueResolver());
            valueResolvers.add(new ItemNumPartsValueResolver());
            valueResolvers.add(new ItemPriceSourceValueResolver());
            valueResolvers.add(new ItemListPriceValueResolver());
            valueResolvers.add(new ItemStatusValueResolver());
            valueResolvers.add(new MethodOfPOTransmissionValueResolver());
            valueResolvers.add(new ObjectCodeValueResolver());
            valueResolvers.add(new OrderTypeValueResolver());
            valueResolvers.add(new OrgCodeCodeValueResolver());
            valueResolvers.add(new PercentValueResolver());
            valueResolvers.add(new POConfirmationValueResolver());
            valueResolvers.add(new PreqPosstiveApprovalReqValueResolver());
            valueResolvers.add(new ReceivingRequiredValueResolver());
            valueResolvers.add(new ReceiptNoteValueResolver());
            valueResolvers.add(new RecurringPaymentTypeValueResolver());
            valueResolvers.add(new RecurringPaymentBeginDateValueResolver());
            valueResolvers.add(new RecurringPaymentEndDateTypeValueResolver());
            valueResolvers.add(new RequestSourceValueResolver());
            valueResolvers.add(new RequisitionSourceValueResolver());
            valueResolvers.add(new RequestorNoteValueResolver());
            valueResolvers.add(new RouteToRequestorValueResolver());
            valueResolvers.add(new SelectorNoteValueResolver());
            valueResolvers.add(new SingleCopyNumberValueResolver());
            valueResolvers.add(new TaxIndicatorValueResolver());
            valueResolvers.add(new VendorChoiceValueResolver());
            valueResolvers.add(new VendorNumberValueResolver());
            valueResolvers.add(new VendorItemIdentifierValueResolver());
            valueResolvers.add(new VendorCustomerNumberValueResolver());
            valueResolvers.add(new VendorInstructionsNoteValueResolver());
            valueResolvers.add(new DeliveryBuildingRoomNumberValueResolver());
            valueResolvers.add(new SpecialProcessingInstructionNoteValueResolver());
        }
        return valueResolvers;
    }
}
