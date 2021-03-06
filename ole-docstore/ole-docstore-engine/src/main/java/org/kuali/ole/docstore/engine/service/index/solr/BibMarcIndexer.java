package org.kuali.ole.docstore.engine.service.index.solr;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.solr.client.solrj.SolrServer;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.kuali.ole.docstore.OleException;
import org.kuali.ole.docstore.common.constants.DocstoreConstants;
import org.kuali.ole.docstore.common.document.*;
import org.kuali.ole.docstore.common.document.config.DocumentSearchConfig;
import org.kuali.ole.docstore.common.document.content.bib.marc.*;
import org.kuali.ole.docstore.common.document.content.bib.marc.xstream.BibMarcRecordProcessor;
import org.kuali.ole.docstore.common.exception.DocstoreIndexException;
import org.kuali.ole.docstore.common.util.ReindexBatchStatistics;
import org.kuali.ole.docstore.discovery.service.SolrServerManager;
import org.kuali.ole.docstore.indexer.solr.DocumentLocalId;
import org.kuali.ole.docstore.model.enums.DocCategory;
import org.kuali.ole.docstore.model.enums.DocFormat;
import org.kuali.ole.docstore.model.enums.DocType;
import org.kuali.ole.docstore.utility.ISBNUtil;
import org.kuali.ole.utility.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StopWatch;

import java.io.IOException;
import java.text.DateFormat;
import java.text.Normalizer;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created with IntelliJ IDEA.
 * User: sambasivam
 * Date: 12/17/13
 * Time: 4:58 PM
 * To change this template use File | Settings | File Templates.
 */
public class BibMarcIndexer extends DocstoreSolrIndexService implements DocstoreConstants {


    //    public static Map<String, String> FIELDS_TO_TAGS_2_INCLUDE_MAP = new HashMap<String, String>();
//    public static Map<String, String> FIELDS_TO_TAGS_2_EXCLUDE_MAP = new HashMap<String, String>();
    private static final String SEPERATOR_DATA_FIELD = ", ";
    private static final String SEPERATOR_SUB_FIELD = " ";
    private static final String PATTERN_CHAR = "*";
    private static final String SEPERATOR_HYPHEN = " - ";
    private static final String SEPERATOR_DOUBLE_HYPHEN = " -- ";
    private static final String DYNAMIC_FIELD_PREFIX = "mdf_";
    private static final String BIB_IDENTIFIER = "bibIdentifier";
    private static final String HOLDINGS_IDENTIFIER = "holdingsIdentifier";
    private static final String ITEM_IDENTIFIER = "itemIdentifier";
    private String publicationDateRegex = "[0-9]{4}";
    private static final Logger LOG = LoggerFactory
            .getLogger(BibMarcIndexer.class);

    private static BibMarcIndexer bibMarcIndexer = null;

    public static BibMarcRecordProcessor recordProcessor = new BibMarcRecordProcessor();
    private static DocumentSearchConfig documentSearchConfig = null;

//    static {
//        DocumentSearchConfig.getDocumentSearchConfig();
//        FIELDS_TO_TAGS_2_INCLUDE_MAP = Collections.unmodifiableMap(DocumentSearchConfig.FIELDS_TO_TAGS_2_INCLUDE_MAP);
//        FIELDS_TO_TAGS_2_EXCLUDE_MAP = Collections.unmodifiableMap(DocumentSearchConfig.FIELDS_TO_TAGS_2_EXCLUDE_MAP);
//    }

    public static BibMarcIndexer getInstance() {
        if (bibMarcIndexer == null) {
            bibMarcIndexer = new BibMarcIndexer();
        }
        documentSearchConfig = DocumentSearchConfig.getDocumentSearchConfig();
        return bibMarcIndexer;
    }


    @Override
    public void createTree(Object object) {
        List<SolrInputDocument> solrInputDocuments = new ArrayList<>();
        buildSolrDocsForBibTree((BibTree) object, solrInputDocuments);
        indexSolrDocuments(solrInputDocuments, true);
    }

    /**
     * Build Solr input document for bib tree
     *
     * @param bibTree
     * @param solrInputDocuments
     */
    private void buildSolrDocsForBibTree(BibTree bibTree, List<SolrInputDocument> solrInputDocuments) {
        Bib bib = bibTree.getBib();
        if (bib.getId() != null && bib.getId().contains("wbm")) {
            BibMarcRecords bibMarcRecords = recordProcessor.fromXML(bib.getContent());
            SolrInputDocument bibSolrDoc = buildSolrInputDocument(bibMarcRecords.getRecords().get(0));
            setCommonFields(bib, bibSolrDoc);
            solrInputDocuments.add(bibSolrDoc);
            for (HoldingsTree holdingsTree : bibTree.getHoldingsTrees()) {
                if (holdingsTree.getHoldings() != null && holdingsTree.getHoldings().getOperation() != null && holdingsTree.getHoldings().getOperation().equals(DocstoreDocument.OperationType.NONE)) {
                    continue;
                }
                buildSolrDocsForHoldingsTree(solrInputDocuments, bib, bibSolrDoc, holdingsTree);
            }
        }
    }


    /**
     * Building Holdings Tree  Solr document
     *
     * @param solrInputDocuments
     * @param bib
     * @param bibSolrDoc
     * @param holdingsTree
     */
    private void buildSolrDocsForHoldingsTree(List<SolrInputDocument> solrInputDocuments, Bib bib, SolrInputDocument bibSolrDoc, HoldingsTree holdingsTree) {
        if (holdingsTree.getHoldings() != null) {
            HoldingsOlemlIndexer holdingsOlemlIndexer = HoldingsOlemlIndexer.getInstance();
            if (holdingsTree.getHoldings().getContent() != null || holdingsTree.getHoldings().getContentObject() != null) {
                SolrInputDocument holdingsSolrInputDoc = holdingsOlemlIndexer.getSolrInputFieldsForHoldings(holdingsTree.getHoldings());
                linkHoldingsWithBib(bibSolrDoc, holdingsSolrInputDoc, bib.getId(), solrInputDocuments, holdingsTree.getHoldings().getId());
                holdingsSolrInputDoc.addField(BIB_IDENTIFIER, bib.getId());
                List<Item> itemDocuments = holdingsTree.getItems();
                List<String> itemIds = new ArrayList<String>();
                holdingsSolrInputDoc.addField(ITEM_IDENTIFIER, itemIds);
                ItemOlemlIndexer itemOlemlIndexer = ItemOlemlIndexer.getInstance();
                addHoldingsInfoToBib(holdingsSolrInputDoc, bibSolrDoc);
                for (Item itemDocument : itemDocuments) {
                    itemIds.add(itemDocument.getId());
                    SolrInputDocument itemSolrInputDoc = itemOlemlIndexer.getSolrInputFieldsForItem(itemDocument);
                    itemSolrInputDoc.addField(HOLDINGS_IDENTIFIER, holdingsTree.getHoldings().getId());
                    itemSolrInputDoc.addField(BIB_IDENTIFIER, bib.getId());
                    addBibInfoForHoldingsOrItems(itemSolrInputDoc, holdingsSolrInputDoc);
                    addHoldingsInfoToItem(itemSolrInputDoc, holdingsSolrInputDoc);
                    addItemInfoToBib(itemSolrInputDoc, bibSolrDoc);
                    solrInputDocuments.add(itemSolrInputDoc);
                }

                solrInputDocuments.add(holdingsSolrInputDoc);
            } else if (StringUtils.isNotEmpty(holdingsTree.getHoldings().getId())) {
                bibSolrDoc.addField(HOLDINGS_IDENTIFIER, "who-" + holdingsTree.getHoldings().getId());
            }
        }
    }

    private void linkHoldingsWithBib(SolrInputDocument bibSolrDoc, SolrInputDocument holdingsSolrInputDoc, String bibId, List<SolrInputDocument> solrInputDocuments, String holdingsId) {
        if (bibSolrDoc == null) {
            SolrDocument bibSolrDocument = getSolrDocumentByUUID(bibId);
            bibSolrDoc = buildSolrInputDocFromSolrDoc(bibSolrDocument);
            solrInputDocuments.add(bibSolrDoc);
        }
        bibSolrDoc.addField(HOLDINGS_IDENTIFIER, holdingsId);
        addBibInfoForHoldingsOrItems(holdingsSolrInputDoc, bibSolrDoc);
    }

    @Override
    public void createTrees(Object object) {
        BibTrees bibTreesObj = (BibTrees) object;
        List<BibTree> bibTrees = bibTreesObj.getBibTrees();
        List<SolrInputDocument> solrInputDocuments = new ArrayList<>();
        for (BibTree bibTree : bibTrees) {
            buildSolrDocsForBibTree(bibTree, solrInputDocuments);
        }

        indexSolrDocuments(solrInputDocuments, true);
    }

    /**
     * process Bib Trees and commit once to solr.
     * Delete documents and commit once
     *
     * @param bibTrees
     */
    @Override
    public void processBibTrees(BibTrees bibTrees) {
        List<SolrInputDocument> solrInputDocuments = new ArrayList<>();
        List<String> idsToDelete = new ArrayList<>();
        for (BibTree bibTree : bibTrees.getBibTrees()) {
            processBibTree(bibTree, solrInputDocuments, idsToDelete);
        }
        LOG.info("Solr Input Documents Size : " + solrInputDocuments.size());
        indexAndDelete(solrInputDocuments, idsToDelete, true);
    }

    /**
     * Process Bib tree
     *
     * @param bibTree
     * @param solrInputDocuments
     * @param idsToDelete
     */
    private void processBibTree(BibTree bibTree, List<SolrInputDocument> solrInputDocuments, List<String> idsToDelete) {
        Bib bib = bibTree.getBib();
        if (null != bib) {
            SolrInputDocument bibSolrInputDocument = new SolrInputDocument();
            if (Bib.ResultType.SUCCESS.equals(bib.getResult())) {
                if (bib.getId() != null) {
                    if (Bib.OperationType.CREATE.equals(bib.getOperation())) {
                        createBibTreeDocforSolr(bibTree, solrInputDocuments);
                    } else if (Bib.OperationType.UPDATE.equals(bib.getOperation())) {
                        updateBibDocument(bib, solrInputDocuments, bibSolrInputDocument);
                        processHoldingsTrees(bibTree.getHoldingsTrees(), bibSolrInputDocument, solrInputDocuments, idsToDelete);
                    } else if (Bib.OperationType.DELETE.equals(bib.getOperation())) {
                        idsToDelete.add(bib.getId());
                    }
                }
            } else if (bib.getOperation() == null || StringUtils.isBlank(bib.getOperation().name())) {
                processHoldingsTrees(bibTree.getHoldingsTrees(), bibSolrInputDocument, solrInputDocuments, idsToDelete);
            }
        }
    }

    /**
     * @param bibTree
     * @param solrInputDocuments
     */
    private void createBibTreeDocforSolr(BibTree bibTree, List<SolrInputDocument> solrInputDocuments) {
        buildSolrDocsForBibTree(bibTree, solrInputDocuments);
    }

    /**
     * @param holdingsTrees
     * @param bibSolrInputDocument
     * @param solrInputDocuments
     * @param idsToDelete
     */
    private void processHoldingsTrees(List<HoldingsTree> holdingsTrees, SolrInputDocument bibSolrInputDocument, List<SolrInputDocument> solrInputDocuments, List<String> idsToDelete) {
        for (HoldingsTree holdingsTree : holdingsTrees) {
            processHoldingsTree(holdingsTree, bibSolrInputDocument, solrInputDocuments, idsToDelete);
        }
    }


    /**
     * Proocess Holdings Tree and add Holdings tree  to solr input documents
     *
     * @param holdingsTree
     * @param bibSolrInputDocument
     * @param solrInputDocuments
     * @param idsToDelete          *
     */
    private void processHoldingsTree(HoldingsTree holdingsTree, SolrInputDocument bibSolrInputDocument, List<SolrInputDocument> solrInputDocuments, List<String> idsToDelete) {
        HoldingsOlemlIndexer holdingsOlemlIndexer = HoldingsOlemlIndexer.getInstance();
        Holdings holdings = holdingsTree.getHoldings();
        SolrInputDocument holdingsSolrInputDocument = new SolrInputDocument();

        if (Holdings.ResultType.SUCCESS.equals(holdings.getResult())) {
            if (holdings.getId() != null) {
                if (Holdings.OperationType.CREATE.equals(holdings.getOperation())) {
                    Bib bib = holdings.getBib();
                    if (null != bib && null != bib.getId()) {
                        buildSolrDocsForHoldingsTree(solrInputDocuments, bib, bibSolrInputDocument, holdingsTree);
                    }
                } else if (Holdings.OperationType.UPDATE.equals(holdings.getOperation())) {
                    holdingsOlemlIndexer.processHoldingSolrDocumentForUpdate(holdings, solrInputDocuments, holdingsSolrInputDocument);
                    processItems(holdingsTree.getItems(), solrInputDocuments, holdingsSolrInputDocument, idsToDelete);
                } else if (Holdings.OperationType.DELETE.equals(holdings.getOperation())) {
                    idsToDelete.add(holdings.getId());
                    for(Item item:holdingsTree.getItems()){
                        idsToDelete.add(item.getId());
                    }
                    holdingsOlemlIndexer.processDelete(holdings.getId(), solrInputDocuments);
                }
            }
        } else if ((holdings.getOperation() == null || StringUtils.isBlank(holdings.getOperation().name()))) {
            processItems(holdingsTree.getItems(), solrInputDocuments, holdingsSolrInputDocument, idsToDelete);
        }

    }


    /**
     * Process items and add items to solr input documents
     *
     * @param items
     * @param solrInputDocuments
     * @param holdingsSolrInputDocument
     * @param idsToDelete
     */
    private void processItems(List<Item> items, List<SolrInputDocument> solrInputDocuments, SolrInputDocument holdingsSolrInputDocument, List<String> idsToDelete) {
        ItemOlemlIndexer itemOlemlIndexer = ItemOlemlIndexer.getInstance();
        for (Item item : items) {
            if (Item.ResultType.SUCCESS.equals(item.getResult())) {
                if (item.getId() != null) {
                    if (Item.OperationType.CREATE.equals(item.getOperation())) {
                        itemOlemlIndexer.buildSolrInputDocumentForBatchProcess(item, solrInputDocuments, holdingsSolrInputDocument);
                    } else if (Item.OperationType.UPDATE.equals(item.getOperation())) {
                        itemOlemlIndexer.updateRecordInSolr(item, solrInputDocuments);
                    } else if (Item.OperationType.DELETE.equals(item.getOperation())) {
                        idsToDelete.add(item.getId());
                        itemOlemlIndexer.processDelete(item.getId(), solrInputDocuments);
                    }
                }
            }
        }
    }

    public void createTrees(Object object, ReindexBatchStatistics reindexBatchStatistics) {
        BibTrees bibTreesObj = (BibTrees) object;
        List<BibTree> bibTrees = bibTreesObj.getBibTrees();
        List<SolrInputDocument> solrInputDocuments = new ArrayList<>();
        StopWatch stopWatch = new StopWatch();

        stopWatch.start();
        for (BibTree bibTree : bibTrees) {
            buildSolrDocsForBibTree(bibTree, solrInputDocuments);
        }
        stopWatch.stop();
        reindexBatchStatistics.addBuildSolrDocsTime(stopWatch.getTotalTimeMillis());

        indexSolrDocuments(solrInputDocuments, true, reindexBatchStatistics);
    }

    protected void indexSolrDocuments(List<SolrInputDocument> solrDocs, boolean isCommit, ReindexBatchStatistics reindexBatchStatistics) {
        SolrServer solr = null;
        try {
            solr = SolrServerManager.getInstance().getSolrServer();
            StopWatch stopWatch = new StopWatch();
            stopWatch.start("add");
            UpdateResponse response = solr.add(solrDocs);
            stopWatch.stop();
            reindexBatchStatistics.addRecToAddInSolr(stopWatch.getLastTaskTimeMillis());
            if (isCommit) {
                stopWatch.start("commit");
                solr.commit(false, false);
                stopWatch.stop();
                reindexBatchStatistics.addCommitTime(stopWatch.getLastTaskTimeMillis());
            }
        } catch (SolrServerException e) {
            LOG.info("Exception :", e);
            rollback(solr);
            throw new DocstoreIndexException(e.getMessage());
        } catch (IOException e) {
            LOG.info("Exception :", e);
            rollback(solr);
            throw new DocstoreIndexException(e.getMessage());
        }
    }


    protected void buildSolrInputDocument(Object object, List<SolrInputDocument> solrInputDocuments) {
        Bib bib = (Bib) object;
        BibMarcRecords bibMarcRecords = recordProcessor.fromXML(bib.getContent());
        SolrInputDocument solrInputDocument = buildSolrInputDocument(bibMarcRecords.getRecords().get(0));

        setCommonFields(bib, solrInputDocument);

        solrInputDocuments.add(solrInputDocument);

    }

    protected void setCommonFields(Bib bib, SolrInputDocument solrInputDocument) {
        solrInputDocument.setField(ID, bib.getId());
        solrInputDocument.addField(LOCALID_SEARCH, DocumentLocalId.getDocumentId(bib.getId()));
        solrInputDocument.addField(LOCALID_DISPLAY, DocumentLocalId.getDocumentIdDisplay(bib.getId()));
        solrInputDocument.addField(UNIQUE_ID, bib.getId());
        solrInputDocument.setField(DOC_CATEGORY, DocCategory.WORK.getCode());
        solrInputDocument.setField(BIB_ID, bib.getId());

        solrInputDocument.setField(STATUS_SEARCH, bib.getStatus());
        solrInputDocument.setField(STATUS_DISPLAY, bib.getStatus());

        if (StringUtils.isNotEmpty(bib.getStatusUpdatedOn())) {
            solrInputDocument.setField(STATUS_UPDATED_ON, getDate(bib.getStatusUpdatedOn()));
        }

        solrInputDocument.addField(STAFF_ONLY_FLAG, bib.isStaffOnly());

        String createdBy = bib.getCreatedBy();
        solrInputDocument.setField(CREATED_BY, createdBy);
        solrInputDocument.setField(UPDATED_BY, createdBy);

        Date date = new Date();
        Date createdDate = null;

        if (StringUtils.isNotBlank(bib.getCreatedOn())) {
            createdDate = getDate(bib.getCreatedOn());
            solrInputDocument.setField(DATE_ENTERED, createdDate);
        } else {
            solrInputDocument.setField(DATE_ENTERED, date);
        }

        if (StringUtils.isNotBlank(bib.getUpdatedOn())) {
            solrInputDocument.setField(DATE_UPDATED, getDate(bib.getUpdatedOn()));
        } else {
            if (StringUtils.isNotBlank(bib.getCreatedOn())) {
                // Updated date will have created date value when bib is not updated after it is created.
                solrInputDocument.setField(DATE_UPDATED, createdDate);
            } else {
                solrInputDocument.setField(DATE_UPDATED, date);
            }
        }
    }

    protected void updateRecordInSolr(Object object, List<SolrInputDocument> solrInputDocuments) {
        Bib bib = (Bib) object;
        List<SolrDocument> solrDocumentList = getSolrDocumentBySolrId(bib.getId());
        SolrDocument solrDocument = solrDocumentList.get(0);
        SolrInputDocument solrInputDocument = new SolrInputDocument();
        if (bib.getContent() != null) {
            BibMarcRecord workBibMarcRecord = recordProcessor.fromXML(bib.getContent()).getRecords().get(0);
            solrInputDocument = buildSolrInputDocument(workBibMarcRecord);
            if (solrDocument != null && solrDocument.getFieldValue(HOLDINGS_IDENTIFIER) != null) {
                addBibInfoToHoldings(solrInputDocuments, solrInputDocument, solrDocument);
            }
            if (StringUtils.isNotEmpty(bib.getStatusUpdatedOn())) {
                solrInputDocument.setField(STATUS_UPDATED_ON, getDate(bib.getStatusUpdatedOn()));
            }
        } else {
            buildSolrInputDocFromSolrDoc(solrDocument, solrInputDocument);
        }
        setCommonFieldsForSolrDoc(solrInputDocument, bib, solrDocument);
        solrInputDocuments.add(solrInputDocument);
    }

    /**
     * Updating Bib solr document with holding and other details
     *
     * @param object
     * @param solrInputDocuments
     * @param solrbibInputDocument
     */
    protected void updateBibDocument(Object object, List<SolrInputDocument> solrInputDocuments, SolrInputDocument solrbibInputDocument) {
        Bib bib = (Bib) object;
        List<SolrDocument> solrDocumentList = getSolrDocumentBySolrId(bib.getId());
        SolrDocument solrDocument = solrDocumentList.get(0);

        if (bib.getContent() != null) {
            BibMarcRecord workBibMarcRecord = recordProcessor.fromXML(bib.getContent()).getRecords().get(0);
            solrbibInputDocument = buildSolrInputDocument(workBibMarcRecord, solrbibInputDocument);

            if (solrDocument != null && solrDocument.getFieldValue(HOLDINGS_IDENTIFIER) != null) {
                addBibInfoToHoldings(solrInputDocuments, solrbibInputDocument, solrDocument);
            }
            if (StringUtils.isNotEmpty(bib.getStatus()) || StringUtils.isNotEmpty(bib.getStatusUpdatedOn())) {
                solrbibInputDocument.setField(STATUS_UPDATED_ON, getDate(bib.getStatusUpdatedOn()));
            }
        } else {
            buildSolrInputDocFromSolrDoc(solrDocument, solrbibInputDocument);
        }
        setCommonFieldsForSolrDoc(solrbibInputDocument, bib, solrDocument);
        solrInputDocuments.add(solrbibInputDocument);

    }

    private void addBibInfoToHoldings(List<SolrInputDocument> solrInputDocuments, SolrInputDocument bibSolrDoc, SolrDocument solrDocument) {
        Object instanceIdentifier = solrDocument.getFieldValue(HOLDINGS_IDENTIFIER);
        bibSolrDoc.addField(HOLDINGS_IDENTIFIER, instanceIdentifier);
        List<String> holdinsgsIds = new ArrayList<>();
        if (instanceIdentifier instanceof String) {
            holdinsgsIds.add((String) instanceIdentifier);
        } else {
            holdinsgsIds.addAll((List<String>) instanceIdentifier);
        }

        for (String holdingsId : holdinsgsIds) {
            List<SolrDocument> solrDocumentList = getSolrDocumentBySolrId(holdingsId);
            if (CollectionUtils.isNotEmpty(solrDocumentList)) {
                SolrDocument holdingsSolrDocument = solrDocumentList.get(0);
                SolrInputDocument holdingsSolrInputDocument = new SolrInputDocument();
                buildSolrInputDocFromSolrDoc(holdingsSolrDocument, holdingsSolrInputDocument);
                removeFieldFromSolrInputDocument(holdingsSolrInputDocument);
                addBibInfoForHoldingsOrItems(holdingsSolrInputDocument, bibSolrDoc);
                List<String> itemIds = new ArrayList<>();

                Object itemIdentifier = holdingsSolrDocument.getFieldValue(ITEM_IDENTIFIER);
                if (itemIdentifier != null) {
                    if (itemIdentifier instanceof String) {
                        itemIds.add((String) itemIdentifier);
                    } else {
                        itemIds.addAll((List<String>) itemIdentifier);
                    }
                }

                for (String itemId : itemIds) {

                    List<SolrDocument> itemDocumentList = getSolrDocumentBySolrId(itemId);
                    SolrDocument itemSolrDocument = itemDocumentList.get(0);
                    SolrInputDocument itemSolrInputDocument = new SolrInputDocument();
                    buildSolrInputDocFromSolrDoc(itemSolrDocument, itemSolrInputDocument);
                    removeFieldFromSolrInputDocument(itemSolrInputDocument);
                    addBibInfoForHoldingsOrItems(itemSolrInputDocument, bibSolrDoc);
                    addHoldingsInfoToItem(itemSolrInputDocument, bibSolrDoc);
                    solrInputDocuments.add(itemSolrInputDocument);
                }
                solrInputDocuments.add(holdingsSolrInputDocument);

            }
        }

    }

    protected void deleteRecordInSolr(SolrServer solrServer, String id) throws IOException, SolrServerException {
        String query = "bibIdentifier:" + id + " OR " + "id:" + id;
        UpdateResponse updateResponse = solrServer.deleteByQuery(query);
        LOG.info("updateResponse " + updateResponse);
        // save deleted  Bibliographic with Bibliographic_delete
        String newId = id + "_d";
        SolrInputDocument solrInputDocument = new SolrInputDocument();
        solrInputDocument.setField("DocType", "bibliographic_delete");
        solrInputDocument.setField("dateUpdated", new Date());
        solrInputDocument.setField("uniqueId", newId);
        solrInputDocument.setField("id", newId);
        solrInputDocument.setField("LocalId_display", DocumentLocalId.getDocumentIdDisplay(id));
        UpdateResponse updateResponseForBib = solrServer.add(solrInputDocument);
        LOG.debug("updateResponse " + updateResponseForBib);

    }

    private void setCommonFieldsForSolrDoc(SolrInputDocument solrInputDocument, Bib bib, SolrDocument solrDocument) {
        solrInputDocument.setField(ID, bib.getId());
        solrInputDocument.addField(UNIQUE_ID, bib.getId());
        solrInputDocument.setField(DOC_CATEGORY, DocCategory.WORK.getCode());
        String updatedBy = bib.getUpdatedBy();
        solrInputDocument.setField(UPDATED_BY, updatedBy);
        solrInputDocument.setField(DATE_UPDATED, new Date());
        solrInputDocument.setField(CREATED_BY, solrDocument.getFieldValue(CREATED_BY));
        solrInputDocument.setField(DATE_ENTERED, solrDocument.getFieldValue(DATE_ENTERED));
        solrInputDocument.setField(BIB_ID, bib.getId());
        solrInputDocument.addField(LOCALID_SEARCH, DocumentLocalId.getDocumentId(bib.getId()));
        solrInputDocument.addField(LOCALID_DISPLAY, DocumentLocalId.getDocumentIdDisplay(bib.getId()));
        solrInputDocument.addField(STAFF_ONLY_FLAG, bib.isStaffOnly());
        solrInputDocument.setField(STATUS_SEARCH, bib.getStatus());
        solrInputDocument.setField(STATUS_DISPLAY, bib.getStatus());
    }


    private Date getDate(String dateStr) {
        DateFormat format = new SimpleDateFormat(Constants.DATE_FORMAT);
        try {
            if (StringUtils.isNotEmpty(dateStr)) {
                return format.parse(dateStr);
            } else {
                return new Date();
            }

        } catch (ParseException e) {
            LOG.info("Exception : " + dateStr + " for format:: " + Constants.DATE_FORMAT, e);
            return new Date();
        }
    }


    /**
     * Method to build Solr Input Document from a given Work Bib Marc Record
     *
     * @param record
     * @return
     */
    public SolrInputDocument buildSolrInputDocument(BibMarcRecord record) {
        SolrInputDocument solrDoc = new SolrInputDocument();
        buildSolrInputDocument(record, solrDoc);
        return solrDoc;
    }

    public SolrInputDocument buildSolrInputDocument(BibMarcRecord record, SolrInputDocument solrDoc) {

        solrDoc.addField(LEADER, record.getLeader());

        // Title Field Calculations.
        List<ControlField> controlFieldList = record.getControlFields();

        for (ControlField cf : controlFieldList) {
            solrDoc.addField("controlfield_" + cf.getTag(), cf.getValue());
        }

        solrDoc.addField(DOC_TYPE, DocType.BIB.getDescription());
        solrDoc.addField(DOC_FORMAT, DocFormat.MARC.getDescription());

        for (String field : documentSearchConfig.FIELDS_TO_TAGS_2_INCLUDE_MAP.keySet()) {
            Object object = buildFieldValue(field, record);
            if(object != null){
                addFieldToSolrDoc(record, field,object, solrDoc);
            }

        }
        addFieldToSolrDoc(record, ALL_TEXT, getAllText(record), solrDoc);
        addGeneralFieldsToSolrDoc(record, solrDoc);
        if (record.getLeader() == null || ((record.getLeader().length() >= 8) && (record.getLeader().charAt(7) != 's'))) {
            solrDoc.removeField(JOURNAL_TITLE_SEARCH);
            solrDoc.removeField(JOURNAL_TITLE_DISPLAY);
            solrDoc.removeField(JOURNAL_TITLE_SORT);
        }
        addFieldToSolrDoc(record, PUBLISHER_SORT, solrDoc.getFieldValue(PUBLISHER_SEARCH), solrDoc);
        return solrDoc;
    }

    private void addFieldToSolrDoc(BibMarcRecord record, String fieldName, Object value,
                                   SolrInputDocument solrDoc) {
        int ind2Value = 0;
        if (value instanceof List) {
            if (fieldName.toLowerCase().endsWith("_sort")) // Sort fields only the first value to be inserted.
            {
                ind2Value = getSecondIndicator(record, fieldName);
                LOG.debug("field name -->" + fieldName + "----->" + ind2Value);
                if (ind2Value > 0) {
                    solrDoc.addField(fieldName, ((List) value).get(0).toString().substring(ind2Value));
                } else {
                    solrDoc.addField(fieldName, ((List) value).get(0));
                }

            } else if (fieldName.endsWith("_facet")) {
                solrDoc.addField(fieldName, getSortString((List) value));
            } else {
                if (((List) value).size() > 0) {
                    for (Object obj : (List<Object>) value)
                    // All non Sort and Multi Valued Fields
                    {
                        solrDoc.addField(fieldName, obj);
                    }
                } else {
                    solrDoc.addField(fieldName, null);
                }
            }
        } else {
            if (fieldName.toLowerCase().endsWith("_sort")) // Sort fields only the first value to be inserted.
            {
                ind2Value = getSecondIndicator(record, fieldName);
                LOG.debug("field name -->" + fieldName + "----->" + ind2Value);
                if (value != null && ind2Value > 0) {
                    String fieldValue = value.toString();
                    try {
                        fieldValue = value.toString().substring(ind2Value);
                    } catch (Exception e) {
                        LOG.error("Exception while getting value:" + value.toString() + " for field:" + fieldName + ". Exception:" + e.toString());
                        // TODO: log the exception trace here.
                    }
                    solrDoc.addField(fieldName, fieldValue);
                } else {
                    solrDoc.addField(fieldName, value);
                }
            } else if (fieldName.endsWith("_facet")) {
                if (value != null) {
                    solrDoc.addField(fieldName, getSortString(value.toString()));
                }
            } else {
                solrDoc.addField(fieldName, value);
            }
        }
    }

    /**
     * Method to build Field Value for a given field Name and given record.
     *
     * @param fieldName - field name should be one of the defined names in WorkBibMarcDocBuilder
     * @param record    - WorkBibMarcRecord
     * @return - returns the field value build over from the given record.
     */
    public Object buildFieldValue(String fieldName, BibMarcRecord record) {
        List<ControlField> controlFieldList = record.getControlFields();
        List<DataField> dataFields = record.getDataFields();
        String includeTags = documentSearchConfig.FIELDS_TO_TAGS_2_INCLUDE_MAP.get(fieldName);
        if ((includeTags != null) && (includeTags.length() > 0)) {
            String excludeTags = documentSearchConfig.FIELDS_TO_TAGS_2_EXCLUDE_MAP.get(fieldName);
            if (excludeTags == null) {
                excludeTags = "";
            }
            if (fieldName.startsWith("Subject_")) {
                return getDataFieldValue(includeTags, excludeTags, record, true, fieldName);
            } else {
                if (fieldName.equals(ISBN_SEARCH))
                    return normalizeIsbn(getDataFieldValue(includeTags, excludeTags, record, false, fieldName));
                else
                    return getDataFieldValue(includeTags, excludeTags, record, false, fieldName);
            }
        } else if (fieldName.equals(PUBLICATIONDATE_DISPLAY) || fieldName.equals(PUBLICATIONDATE_SEARCH) || fieldName.equals(PUBLICATIONDATE_FACET)
                || fieldName.equals(PUBLICATIONDATE_SORT)) {
            String publicationDate = "";
            String publicationEndDate = "";
            Object publicationDateValue = null;
            for (ControlField controlField : controlFieldList) {
                if (controlField.getTag().equalsIgnoreCase("008")) {
                    String controlField008 = controlField.getValue();
                    if (controlField008 != null && controlField008.length() > 10) {
                        publicationDate = controlField008.substring(7, 11);
                        publicationDate = extractPublicationDateWithRegex(publicationDate);
                        if (controlField008.length() > 14) {
                            publicationEndDate = controlField008.substring(11, 15);
                            publicationEndDate = extractPublicationDateWithRegex(publicationEndDate);
                        }
                    }
                }
            }
            if (publicationDate == null || publicationDate.trim().length() == 0) {
                if (getDataFieldValue("260-c", "", record, true, fieldName) instanceof String) {
                    publicationDate = (String) getDataFieldValue("260-c", "", record, true, fieldName);
                } else if (getDataFieldValue("260-c", "", record, true, fieldName) instanceof List) {
                    publicationDate = ((List<String>) getDataFieldValue("260-c", "", record, true, fieldName)).get(0);
                }
                publicationDate = extractPublicationDateWithRegex(publicationDate);
            }
            if (fieldName.equals(PUBLICATIONDATE_FACET)) {
                if (publicationDate.equalsIgnoreCase("")) {
                    publicationDateValue = "Date could not be determined";
                } else {
                    publicationDateValue = buildPublicationDateFacetValue(publicationDate, publicationEndDate);
                }
                return publicationDateValue;
            }
            return publicationDate;
        } else if (fieldName.equals(LANGUAGE_DISPLAY) || fieldName.equals(LANGUAGE_SEARCH) || fieldName.equals(LANGUAGE_FACET)) {
            List<Object> langs = new ArrayList<Object>();
            for (ControlField controlField : controlFieldList) {
                if (controlField.getTag().equalsIgnoreCase("008")) {
                    String cf8 = controlField.getValue();
                    if (cf8 != null && cf8.length() > 37) {
                        String lang = Languages.getInstance(Languages.ISO_639_3).getLanguageDescription(
                                cf8.substring(35, 38));
                        langs.add(lang == null ? "Undefined" : lang);
                    }
                }
            }
            if (fieldName.equals(LANGUAGE_SEARCH) || fieldName.equals(LANGUAGE_FACET)) {
                for (DataField df : dataFields) {
                    if (df.getTag().equals("546")) {
                        try {
                            for (SubField subfield : df.getSubFields()) {
                                if (subfield.getCode().equalsIgnoreCase("a")) {
                                    langs.add(subfield.getValue());
                                }
                            }
                        } catch (RuntimeException re) {
                            LOG.info("Exception :", re);
                        }
                    }
                }
            }
            return langs;
        } else if (fieldName.equals(FORMAT_DISPLAY) || fieldName.equals(FORMAT_SEARCH) || fieldName.equals(FORMAT_FACET)) {
            return getRecordFormat(record);
        }  else if (fieldName.equals(RESOURCETYPE_DISPLAY) || fieldName.equals(RESOURCETYPE_SEARCH)) {
            return getRecordFormat_ResourceType(record);
        }  else if (fieldName.equals(CARRIER_DISPLAY) || fieldName.equals(CARRIER_SEARCH)) {
            return getRecordFormat_Carrier(record);
        } else if(fieldName.equals(DESCRIPTION_SEARCH)) {
            String excludeTags = documentSearchConfig.FIELDS_TO_TAGS_2_EXCLUDE_MAP.get(fieldName);
            if (excludeTags == null) {
                excludeTags = "";
            }
            if (includeTags == null) {
                includeTags = "";
            }
            return getDataFieldValue(includeTags, excludeTags, record, false, fieldName);
        } else {
            return null;
        }
    }

    /**
     * Method to give all_text field to a given record.
     *
     * @param record
     * @return
     */
    public String getAllText(BibMarcRecord record) {
        StringBuilder allText = new StringBuilder();
        allText.append(record.getLeader());
        allText.append(SEPERATOR_DATA_FIELD);
        for (ControlField cf : record.getControlFields()) {
            allText.append(cf.getValue());
            allText.append(SEPERATOR_DATA_FIELD);
        }
        for (DataField df : record.getDataFields()) {
            for (SubField sf : df.getSubFields()) {
                allText.append(sf.getValue());
                allText.append(SEPERATOR_SUB_FIELD);
                if(sf.getValue().contains("-")){
                    allText.append(sf.getValue().replace("-",""));
                    allText.append(SEPERATOR_SUB_FIELD);
                }
            }
            allText.append(SEPERATOR_DATA_FIELD);
        }
        return allText.toString();
    }

    /**
     * Method to get Record Format.
     *
     * @param record
     * @return
     */
    public String getRecordFormat(BibMarcRecord record) {
        String format = null;
        String cF7 = null;
        String cF8 = null;
        String formatData = "";
        char cF8Ch21 = ' ';
        char cF8Ch22 = ' ';
        char cF8Ch28 = ' ';
        char cF7Ch0 = ' ';
        int cFIndex = record.getControlFields().indexOf(new ControlField("007"));
        if (cFIndex != -1) {
            cF7 = record.getControlFields().get(cFIndex).getValue();
        }
        cFIndex = record.getControlFields().indexOf(new ControlField("008"));
        if (cFIndex != -1) {
            cF8 = record.getControlFields().get(cFIndex).getValue();
        }
        Object tmp = null;
        tmp = getDataFieldValue("111-a", "", record, false, "");
        String dF111a = tmp != null ? tmp.toString() : null;
        tmp = getDataFieldValue("254-h", "", record, false, "");
        String dF254h = tmp != null ? tmp.toString() : null;
        tmp = getDataFieldValue("254-k", "", record, false, "");
        String dF254k = tmp != null ? tmp.toString() : null;
        tmp = getDataFieldValue("260-b", "", record, false, "");
        String dF260b = tmp != null ? tmp.toString() : null;
        tmp = getDataFieldValue("502-a", "", record, false, "");
        String dF502a = tmp != null ? tmp.toString() : null;
        tmp = getDataFieldValue("711-a", "", record, false, "");
        String dF711a = tmp != null ? tmp.toString() : null;

        if (cF8 != null && cF8.length() > 22) {
            cF8Ch21 = cF8.charAt(21);
            cF8Ch22 = cF8.charAt(22);
        }
        if (cF8 != null && cF8.length() > 28) {
            cF8Ch28 = cF8.charAt(28);
        }
        if (cF7 != null) {
            cF7Ch0 = cF7.charAt(0);
        }
        if (record.getLeader() != null && record.getLeader().length() > 8) {
            formatData = record.getLeader().substring(6, 8);
        }

        if (dF254h != null && dF254h.contains("micro")) {
            format = "Microformat";
        } else if (formatData.equals("tm") && dF502a != null) {
            format = "Thesis/Dissertation";
        } else if (dF111a != null || dF711a != null) {
            format = "Conference/Event";
        } else if (formatData.equals("aa") || formatData.equals("am") || formatData.equals("ac") || formatData
                .equals("tm")) {
            if (dF254k != null && dF254k.contains("kit")) {
                format = "Other";
            } else {
                format = "Book";
            }
        } else if (formatData.equals("im") || formatData.equals("jm") || formatData.equals("jc")
                || formatData.equals("jd") || formatData.equals("js")) {
            format = "Sound recording";
        } else if (formatData.equals("cm") || formatData.equals("dm") || formatData.equals("ca")
                || formatData.equals("cb") || formatData.equals("cd") || formatData.equals("cs")) {
            format = "Musical score";
        } else if (formatData.equals("fm") || ("".equals(formatData) && formatData.startsWith("e"))) {
            format = "Map/Atlas";
        } else if (formatData.equals("gm") || (cF7 != null && (cF7Ch0 == ('v')))) {
            format = "Video";
        } else if (formatData.equals("gm") || (cF7 != null && (cF7Ch0 == ('g')))) {
            format = "Projected graphic";
        } else if (formatData.equals("as") || formatData.equals("gs")) {
            format = "Journal/Periodical";
        } else if (formatData.equals("km")) {
            format = "Image";
        } else if (formatData.equals("mm")) {
            format = "Datafile";
        } else if (formatData.equals("as") && (cF8Ch21 == 'n' || cF8Ch22 == 'e')) {
            format = "Newspaper";
        } else if ("".equals(formatData) && formatData.startsWith("r")) {
            format = "3D object";
        } else if (formatData != "" && formatData.endsWith("i")) {
            format = "Database/Website";
        } else if (("".equals(formatData) && (!formatData.startsWith("c") || !formatData.startsWith("d")
                || !formatData.startsWith("i") || !formatData.startsWith("j"))) && (
                (cF8Ch28 == 'f' || cF8Ch28 == 'i' || cF8Ch28 == 'o') && (dF260b != null && !dF260b
                        .contains("press")))) {
            format = "Government document";
        } else {
            format = "Other";
        }
        return format;
    }

    /**
     * Method to get Record Format.
     *
     * @param record
     * @return
     */
    public String getRecordFormat_ResourceType(BibMarcRecord record) {
        String format = null;
        char leader6 = ' ';
        char leader7 = ' ';
        if (record.getLeader() != null) {
            String leader = record.getLeader().trim();
            if (StringUtils.isNotBlank(leader)) {
                if (leader.length() >= 7) {
                    leader6 = leader.charAt(6);
                }
                if (leader.length() >= 8) {
                    leader7 = leader.charAt(7);
                }

                if ((leader6 == 'a' || leader6 == 't') && leader7 == 'm') {
                    format = "Book";
                }
                if (leader6 == 'a' && leader7 == 's') {
                    format = "Serial";
                }
                if (leader6 == 'c' || leader6 == 'd') {
                    format = "Score";
                }
                if (leader6 == 'j' || leader6 == 'i') {
                    format = "Sound recording";
                }
                if (leader6 == 'e' || leader6 == 'f') {
                    format = "Map";
                }
                if (leader6 == 'g') {
                    format = "Motion picture";
                }
                if (leader6 == 'k') {
                    format = "Photo/Print";
                }
                if (leader6 == 'm') {
                    format = "Computer file";
                }
                if (leader6 == 'p') {
                    format = "Archival materials";
                }
                if (leader6 == 'r') {
                    format = "Artifacts";
                }
            }
        }
        return format;
    }

    public String getRecordFormat_Carrier(BibMarcRecord record) {
        String format = null;
        String cF7 = null;
        String cF8 = null;
        char cF70 = ' ';
        char cF71 = ' ';
        char cF823 = ' ';
        char cF829 = ' ';
        char leader06 = ' ';
        String leader ="";
        if (record.getLeader() != null) {
            leader = record.getLeader();
        }
        for (ControlField controlField : record.getControlFields()) {
            if (controlField.getTag().equals("007")) {
                cF7 = controlField.getValue();
            }else if(controlField.getTag().equals("008")){
                cF8 = controlField.getValue();
            }
        }

        if(StringUtils.isNotBlank(cF7) && cF7.length() >= 1){
            cF70 = cF7.charAt(0);
        }
        if(StringUtils.isNotBlank(cF7) && cF7.length() >= 2){
            cF71 = cF7.charAt(1);
        }
        if(StringUtils.isNotBlank(cF8) && cF8.length() >= 24){
            cF823 = cF8.charAt(23);
        }
        if(StringUtils.isNotBlank(cF8) && cF8.length() >= 30){
            cF829 = cF8.charAt(29);
        }
        if(StringUtils.isNotBlank(leader) && leader.length() >= 7){
            leader06 = leader.charAt(6);
        }

        if(cF70 == 'h'){
            format = "Microform";
            return format;
        }
        if(cF70 == 'c' && cF71 == 'r'){
            format = "Remote e-resource";
            return format;
        }
        if(cF70 == 'c' && cF71 != 'r'){
            format = "Direct access 3-resource";
            return format;
        }
        if((leader06 == 'a' || leader06 == 'c' || leader06 == 'd' || leader06 == 'p' || leader06 == 't') && (cF823 == 'd' || cF823 == 'f' || cF823 == 'r' || cF823 == ' ')){
            format = "Print";
        }
        if((leader06 == 'e' || leader06 == 'f' || leader06 == 'k') && (cF829 == 'd' || cF829 == 'r' || cF829 == ' ')){
            format = "Print";
        }
        return format;
    }

    /**
     * Method to give data field value of a given include tags and exclude tags
     *
     * @param includeTags
     * @param excludeTags
     * @param record
     * @param isHyphenSeperatorFirst - Pass 'false' by default (if it is not a subject field (Currently)).
     *                               - Pass 'true' if it has to get encoded first subfield values with " - ".
     * @param fieldName
     * @return
     */
    private Object getDataFieldValue(String includeTags, String excludeTags, BibMarcRecord record,
                                     boolean isHyphenSeperatorFirst, String fieldName) {
        List<Object> fieldValues = new ArrayList<Object>();
        StringTokenizer includeTagsTokenizer = new StringTokenizer(includeTags, ",");

        while (includeTagsTokenizer.hasMoreElements()) {
            String tag = includeTagsTokenizer.nextToken();
            tag = tag.trim();
            int subFieldIdx = tag.indexOf('-');
            String tagNum = (subFieldIdx == -1) ? tag : tag.substring(0, subFieldIdx);

            for (int i = 0; i < record.getDataFields().size(); i++) {
                DataField dataField = record.getDataFields().get(i);
                if (isValidTag(dataField.getTag(), tagNum)) {
                    String linkValue = "";
                    StringBuilder fieldValue = new StringBuilder();
                    List<SubField> subFields = dataField.getSubFields();
                    if (subFieldIdx != -1) { // Includes only one Sub Field of a main Data Field.
                        if (!excludeTags.contains(tag)) {
                            String subFieldCodes = tag.substring(subFieldIdx + 1, tag.length());
                            boolean isHyphenCodedOnce = false;
                            for (SubField subField : subFields) {
                                if (subFieldCodes.contains(subField.getCode())) {
                                    if (fieldValue.length() != 0) {
                                        if (!isHyphenSeperatorFirst || isHyphenCodedOnce || (
                                                dataField.getTag().endsWith("00") || dataField.getTag().endsWith("10")
                                                        || dataField.getTag().endsWith("11"))) {
                                            fieldValue.append(SEPERATOR_SUB_FIELD);
                                        } else {
                                            fieldValue.append(SEPERATOR_HYPHEN);
                                            isHyphenCodedOnce = true;
                                        }
                                    }
                                    fieldValue.append(subField.getValue());
                                } else if (subField.getCode().equalsIgnoreCase("6")) {
                                    linkValue=generateLinkFiledValue(subField.getValue(),record);
                                }
                            }
                        }
                    } else { // Includes whole Data Field i.e includes All Sub Fields in a datafield
                        boolean isHyphenCodedOnce = false;
                        boolean isFirstSubField = false;
                        for (SubField subField : subFields) {
                            if (!excludeTags.contains(dataField.getTag() + "-" + subField.getCode()) && !excludeTags
                                    .contains(tagNum + "-" + subField.getCode())) {
                                if (fieldValue.length() != 0) {
                                    if (!isHyphenSeperatorFirst || isHyphenCodedOnce || (
                                            dataField.getTag().endsWith("00") || dataField.getTag().endsWith("10")
                                                    || dataField.getTag().endsWith("11"))) {
                                        fieldValue.append(SEPERATOR_SUB_FIELD);
                                    } else if (fieldName != null && (fieldName.equalsIgnoreCase(SUBJECT_FACET)
                                            || fieldName.equalsIgnoreCase(SUBJECT_DISPLAY))) {
                                        if (dataField.getTag().equalsIgnoreCase("630")) {
                                            if (subField.getCode().equals("v") || subField.getCode().equals("x")
                                                    || subField.getCode().equals("y") || subField.getCode().equals("z")) {
                                                fieldValue.append(SEPERATOR_DOUBLE_HYPHEN);
                                            }
                                        } else if (dataField.getTag().equalsIgnoreCase("650") || dataField.getTag()
                                                .equalsIgnoreCase(
                                                        "651")) {
                                            if (isFirstSubField && fieldName.equalsIgnoreCase(SUBJECT_FACET)) {
                                                fieldValues.add(fieldValue.toString().trim());
                                            }
                                            fieldValue.append(SEPERATOR_DOUBLE_HYPHEN);
                                            isFirstSubField = true;
                                        } else {
                                            fieldValue.append(SEPERATOR_SUB_FIELD);
                                        }
                                    } else {
                                        if (fieldName.startsWith("Subject_")) {
                                            fieldValue.append(SEPERATOR_SUB_FIELD);
                                        } else {
                                            fieldValue.append(SEPERATOR_HYPHEN);
                                            isHyphenCodedOnce = true;
                                        }
                                    }
                                }

                                fieldValue.append(subField.getValue());
                            }else {
                                if (subField.getCode().equalsIgnoreCase("6")) {
                                    linkValue=generateLinkFiledValue(subField.getValue(),record);
                                }
                            }
                        }
                    }
                    if ((dataField.getTag().equalsIgnoreCase("650") || dataField.getTag().equalsIgnoreCase("651"))
                            && fieldValue != null && fieldValue.length() > 1 && fieldValue.toString().trim().length() > 1) {
                        String fieldVal = fieldValue.toString().trim();
                        String lastChar = String.valueOf(fieldVal.charAt(fieldVal.length() - 1));
                        if (!lastChar.equalsIgnoreCase(".")) {
                            fieldValue.append(".");
                        }
                    }
                    fieldValue.append(linkValue);
                    fieldValues.add(fieldValue.toString().trim());
                }
            }
        }
        if (fieldValues.size() == 1) {
            return fieldValues.get(0);
        } else if (fieldValues.size() > 0) {
            return fieldValues;
        } else {
            return null;
        }
    }

    private String generateLinkFiledValue(String value, BibMarcRecord bibMarcRecord) {
        List<DataField> dataFields = bibMarcRecord.getDataFields();
        StringBuilder linkFieldValue = new StringBuilder();
        try {
            if (value.indexOf("-") > 0) {
                String[] dataValues = value.split("-");
                String tag = dataValues[0];
                int position = Integer.parseInt(dataValues[1]);
                List<DataField> linkDataFields = new ArrayList<>();
                for (DataField dataField : dataFields) {
                    if (dataField.getTag().equalsIgnoreCase(tag)) {
                        linkDataFields.add(dataField);
                    }
                }

                if (CollectionUtils.isNotEmpty(linkDataFields) && linkDataFields.size() > position - 1) {
                    DataField linkDataField = linkDataFields.get(position - 1);
                    for (SubField subField : linkDataField.getSubFields()) {
                        if (!subField.getCode().equalsIgnoreCase("6")) {
                            linkFieldValue.append(subField.getValue());
                        }
                    }
                }
            }
        }
        catch (Exception e) {
            String recordId = bibMarcRecord.getRecordId();
            String valueOf035a = bibMarcRecord.getDataFieldValue("035", "a");
            String message = "Processing marc record failed for 001 : " + recordId + " , 035-a : " + valueOf035a + " because of '" + value + "' value";
            LOG.error(message);
            e.printStackTrace();
        }
        return linkFieldValue.toString();
    }

    /**
     * Method to validate tag with given allowed tag format supplied.
     *
     * @param tag
     * @param tagFormat
     * @return
     */
    private boolean isValidTag(String tag, String tagFormat) {
        try {
            if (!tagFormat.contains(PATTERN_CHAR)) {
                return tagFormat.equals(tag);
            } else {
                int idx = tagFormat.lastIndexOf(PATTERN_CHAR);
                return isValidTag(tag.substring(0, idx) + tag.substring(idx + PATTERN_CHAR.length(), tag.length()), tagFormat.substring(0, idx)
                        + tagFormat.substring(idx + PATTERN_CHAR.length(), tagFormat.length()));
            }
        } catch (Exception e) {
            LOG.info("Exception :", e);
            return false;
        }
    }

    private void addGeneralFieldsToSolrDoc(BibMarcRecord record, SolrInputDocument solrDoc) {
        String isbnDataFields = documentSearchConfig.FIELDS_TO_TAGS_2_INCLUDE_MAP.get(ISBN_SEARCH);
        for (DataField dataField : record.getDataFields()) {
            String tag = dataField.getTag();
            for (SubField subField : dataField.getSubFields()) {
                String subFieldKey = subField.getCode();
                String subFieldValue = subField.getValue();
                String key = tag + subFieldKey;
                subFieldValue = processGeneralFieldValue(tag, subFieldKey, subFieldValue, isbnDataFields);
                solrDoc.addField(DYNAMIC_FIELD_PREFIX + key, subFieldValue);
            }
        }
    }

    private String processGeneralFieldValue(String tag, String subFieldKey, String subFieldValue, String isbnKey) {
        String value = subFieldValue;
        if (isbnKey.contains(tag) && isbnKey.contains(subFieldKey)) {
            value = (String) normalizeIsbn(subFieldValue);
        }
        return value;
    }

    private Object normalizeIsbn(Object isbnValue) {
        Object result = null;
        ISBNUtil isbnUtil = new ISBNUtil();
        if (isbnValue != null) {
            if (isbnValue instanceof List) {
                result = new ArrayList<String>();
                for (Object obj : (List<Object>) isbnValue) {
                    if (((String) obj).length() > 0) {
                        try {
                            ((List<String>) result).add(isbnUtil.normalizeISBN(obj));
                        } catch (OleException e) {
                            // LOG.error("Exception :", e);
                            ((List<String>) result).add((String) obj + " " + ISBN_NOT_NORMALIZED);
                        }
                    } else {
                        ((List<String>) result).add((String) obj);
                    }
                }
            } else {
                if (((String) isbnValue).length() > 0) {
                    try {
                        result = isbnUtil.normalizeISBN(isbnValue);
                    } catch (OleException e) {
                        //  LOG.error("Exception :", e);
                        result = isbnValue + " " + ISBN_NOT_NORMALIZED;
                    }
                } else {
                    result = isbnValue;
                }
            }
        }
        return result;
    }


    /**
     * @param pubCen
     * @param pubList
     */
    private void pubCentury(int pubCen, List<String> pubList) {
        String pubCentury = String.valueOf(pubCen);
        if (pubCentury.endsWith("1")) {
            if (pubCentury.equalsIgnoreCase("11")) {
                pubList.add(pubCentury + "th Century");
            } else {
                pubList.add(pubCentury + "st Century");
            }
        } else if (pubCentury.endsWith("2")) {
            if (pubCentury.equalsIgnoreCase("12")) {
                pubList.add(pubCentury + "th Century");
            } else {
                pubList.add(pubCentury + "nd Century");
            }
        } else if (pubCentury.endsWith("3")) {
            if (pubCentury.equalsIgnoreCase("13")) {
                pubList.add(pubCentury + "th Century");
            } else {
                pubList.add(pubCentury + "rd Century");
            }
        } else {
            pubList.add(pubCentury + "th Century");
        }

    }


    public String extractPublicationDateWithRegex(String publicationDate) {
        Pattern pattern = Pattern.compile(publicationDateRegex);
        Matcher matcher = pattern.matcher(publicationDate);
        if (matcher.find()) {
            if (matcher.group(0).equalsIgnoreCase("0000")) {
                return "";
            }
            return matcher.group(0);
        } else {
            return "";
        }


    }

    /**
     * @param publicationDate
     * @param publicationEndDate
     * @return
     */
    public Object buildPublicationDateFacetValue(String publicationDate, String publicationEndDate) {
        int pubDat = 0;
        List<String> pubList = new ArrayList<String>();
        Calendar cal = Calendar.getInstance();
        int year = cal.get(Calendar.YEAR);
        if (publicationDate != null && publicationDate.length() == 4 && Integer.parseInt(publicationDate) <= year) {
            int pubStartDate = Integer.parseInt(publicationDate);
            if (publicationEndDate != null && publicationEndDate.length() == 4 && pubStartDate < Integer
                    .parseInt(publicationEndDate)) {
                if (Integer.parseInt(publicationEndDate) > year) {
                    publicationEndDate = String.valueOf(year);
                }
                int pubEndDate = Integer.parseInt(publicationEndDate);
                while (pubStartDate < pubEndDate) {
                    pubStartDate = (pubStartDate / 10) * 10;
                    if (pubStartDate == 0) {
                        pubList.add("Date could not be determined");
                    } else {
                        pubList.add(String.valueOf(pubStartDate) + "s");
                    }
                    pubStartDate = pubStartDate + 10;
                }
                pubStartDate = Integer.parseInt(publicationDate);
                pubEndDate = Integer.parseInt(publicationEndDate);
                while (pubStartDate < pubEndDate) {
                    pubStartDate = (pubStartDate) / 100;
                    pubDat = (pubStartDate) + 1;
                    pubCentury(pubDat, pubList);
                    pubStartDate = pubStartDate * 100 + 100;
                }
            } else {
                pubDat = (pubStartDate / 10) * 10;
                int pubCen = ((pubStartDate) / 100) + 1;
                if (pubDat == 0) {
                    pubList.add("Date could not be determined");
                } else {
                    pubList.add(String.valueOf(pubDat) + "s");
                    pubCentury(pubCen, pubList);
                }
            }
        } else {
            pubList.add("Date could not be determined");
        }
        return pubList;
    }


    /**
     * Builds the facet values for the given publication dates.
     *
     * @param publicationDates
     * @return
     */
    public List<String> buildPublicationDateFacetValues(List<String> publicationDates) {
        List<String> valueList = null;
        if (!CollectionUtils.isEmpty(publicationDates)) {
            valueList = new ArrayList<String>(publicationDates.size());
            for (int i = 0; i < publicationDates.size(); i++) {
                String pubDate = publicationDates.get(i);
                Object pubDt = buildPublicationDateFacetValue(pubDate, "");
                if (pubDt instanceof String) {
                    valueList.add((String) pubDt);
                } else if (pubDt instanceof List) {
                    List<String> pubDateList = (List<String>) pubDt;
                    for (String pubDtVal : pubDateList) {
                        valueList.add(pubDtVal);
                    }
                }
            }
        }
        return valueList;
    }


    public String getSortString(String str) {
        String ret = "";
        StringBuffer sortString = new StringBuffer();
        ret = str.toLowerCase();
        ret = ret.replaceAll("[\\-\\/]", " ");
        ret = ret.replace("&lt;", "");
        ret = ret.replace("&gt;", "");
        ret = ret.replaceAll("[\\.\\,\\;\\:\\(\\)\\{\\}\\'\\!\\?\\\"\\<\\>\\[\\]]", "");
        ret = Normalizer.normalize(ret, Normalizer.Form.NFD).replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        ret = ret.replaceAll("\\s+", " ");
        sortString.append(ret);
        sortString.append(" /r/n!@#$");
        sortString.append(str);
        return sortString.toString();
    }

    public List<String> getSortString(List<String> list) {
        List<String> sortStringList = new ArrayList<String>();
        for (String str : list) {
            sortStringList.add(getSortString(str));
        }
        return sortStringList;
    }

    private int getSecondIndicator(BibMarcRecord record, String fieldName) {
        int ind2Value = 0;
        String fieldTags = documentSearchConfig.FIELDS_TO_TAGS_2_INCLUDE_MAP.get(fieldName);
        String[] tagValueList = null;
        if (fieldTags != null) {
            tagValueList = fieldTags.split(",");
            List<DataField> dataFieldList = record.getDataFields();
            String ind2 = null;
            boolean isVisit = true;
            for (DataField dataField : dataFieldList) {
                String tag = dataField.getTag();
                for (String tagValue : tagValueList) {
                    StringBuffer sb = null;
                    if (fieldName.equalsIgnoreCase(AUTHOR_SORT) || fieldName.equalsIgnoreCase(TITLE_SORT)) {
                        sb = getTagValues(dataField, tag, tagValue);
                        if (sb != null && sb.toString().length() > 0 && isVisit) {
                            ind2 = dataField.getInd2();
                            isVisit = false;
                        }

                    }
                }
            }
            try {
                if (ind2 != null)
                    ind2Value = Integer.parseInt(ind2);

            } catch (Exception e) {
                ind2Value = -1;
            }

        }
        return ind2Value;
    }

    private StringBuffer getTagValues(DataField dataField, String tag, String tagValue) {
        StringBuffer sb = new StringBuffer();
        String[] tags = tagValue.split("-");
        for (String tagName : tags) {
            if (tag.equalsIgnoreCase(tagName)) {
                List<SubField> subFieldList = dataField.getSubFields();
                for (SubField subField : subFieldList) {
                    sb.append(subField.getValue() + " ");
                }

            }
        }

        /*       if (tag.equals(tags[0])) {
            LOG.info("tags-->"+tags[0]);
            LOG.info("length-->"+tags[0].length());
            List<SubField> subFieldList = dataField.getSubFields();
            for (SubField subField : subFieldList) {
                sb.append(subField.getValue() + " ");
            }
        }*/
        return sb;
    }

    public void bind(String holdingsId, List<String> bibIds) throws SolrServerException, IOException {
        List<SolrInputDocument> solrInputDocumentList = new ArrayList<SolrInputDocument>();
        updateHoldingsDocument(holdingsId, bibIds, solrInputDocumentList);
        updateBibDocument(holdingsId, bibIds, solrInputDocumentList);
        LOG.info("solrInputDocumentList-->" + solrInputDocumentList);
        SolrServer server = SolrServerManager.getInstance().getSolrServer();
        UpdateResponse updateResponse = server.add(solrInputDocumentList);
        server.commit();
    }

    private void updateBibDocument(String holdingsId, List<String> bibIds, List<SolrInputDocument> solrInputDocumentList) {
        for (String bibId : bibIds) {
            SolrInputDocument bibSolrInputDocument = new SolrInputDocument();
            bibSolrInputDocument.addField(AtomicUpdateConstants.UNIQUE_ID, bibId);
            Map<String, String> holdingsIdsMap = new HashMap<>();
            holdingsIdsMap.put(AtomicUpdateConstants.ADD, holdingsId);
            bibSolrInputDocument.setField(HOLDINGS_IDENTIFIER, holdingsIdsMap);
            solrInputDocumentList.add(bibSolrInputDocument);
        }
    }

    private void updateHoldingsDocument(String holdingsId, List<String> bibIds, List<SolrInputDocument> solrInputDocumentList) throws SolrServerException {
        SolrInputDocument holdingsSolrInputDocument = new SolrInputDocument();
        holdingsSolrInputDocument.addField(AtomicUpdateConstants.UNIQUE_ID, holdingsId);
        Map<String, List<String>> bibIdsMap = new HashMap<String, List<String>>();
        bibIdsMap.put(AtomicUpdateConstants.ADD, bibIds);
        holdingsSolrInputDocument.setField(BIB_ID, bibIdsMap);
        holdingsSolrInputDocument.setField(IS_BOUND_WITH, Boolean.TRUE);
        solrInputDocumentList.add(holdingsSolrInputDocument);
        updateItemDocsOfHoldings(holdingsId, solrInputDocumentList, bibIdsMap);
    }

    private void updateItemDocsOfHoldings(String holdingsId, List<SolrInputDocument> solrInputDocumentList, Map<String, List<String>> bibIdsMap) {
        SolrDocument holdingsSolrDocument = getSolrDocumentByUUID(holdingsId);
        List<String> itemIdentifierList = null;
        Object itemIdentifier = holdingsSolrDocument.getFieldValue(ITEM_IDENTIFIER);
        if (itemIdentifier instanceof List) {
            itemIdentifierList = (List<String>) itemIdentifier;
        } else if (itemIdentifier instanceof String) {
            itemIdentifierList = new ArrayList<String>();
            itemIdentifierList.add((String) itemIdentifier);
        }
        for (String itemId : itemIdentifierList) {
            SolrInputDocument itemSolrInputDocument = new SolrInputDocument();
            itemSolrInputDocument.addField(AtomicUpdateConstants.UNIQUE_ID, itemId);
            itemSolrInputDocument.setField(BIB_ID, bibIdsMap);
            solrInputDocumentList.add(itemSolrInputDocument);
        }
    }

    public void bindAnalytics(String seriesHoldingsId, List<String> itemIds, String createOrBreak) throws SolrServerException, IOException {
        List<SolrInputDocument> solrInputDocumentList = new ArrayList<SolrInputDocument>();
        updateHoldingsDocument(seriesHoldingsId, itemIds, solrInputDocumentList, createOrBreak);
        updateItemDocument(seriesHoldingsId, itemIds, solrInputDocumentList, createOrBreak);
        LOG.info("solrInputDocumentList-->" + solrInputDocumentList);
        SolrServer server = SolrServerManager.getInstance().getSolrServer();
        UpdateResponse updateResponse = server.add(solrInputDocumentList);
        server.commit();
    }

    private void updateHoldingsDocument(String seriesHoldingsId, List<String> itemIds, List<SolrInputDocument> holdingsSolrInputDocumentList, String createOrBreak) {
        SolrInputDocument holdingsSolrInputDocument = new SolrInputDocument();
        holdingsSolrInputDocument.addField(AtomicUpdateConstants.UNIQUE_ID, seriesHoldingsId);
        Map<String, List<String>> itemIdsMap = new HashMap<String, List<String>>();
        if (createOrBreak.equalsIgnoreCase(CREATE_RELATION)) {
            itemIdsMap.put(AtomicUpdateConstants.ADD, itemIds);
            holdingsSolrInputDocument.setField(ITEM_IDENTIFIER, itemIdsMap);
            holdingsSolrInputDocument.setField(IS_SERIES, Boolean.TRUE);
            holdingsSolrInputDocument.setField(IS_ANALYTIC, Boolean.TRUE);
        } else if (createOrBreak.equalsIgnoreCase(BREAK_RELATION)) {
            itemIdsMap.put(AtomicUpdateConstants.REMOVE, itemIds);
            holdingsSolrInputDocument.addField(ITEM_IDENTIFIER, itemIdsMap);
            if (!hasAnalyticItemInHoldings(seriesHoldingsId, itemIds)) {
                Map analyticMap = new HashMap();
                analyticMap.put(AtomicUpdateConstants.SET, null);
                holdingsSolrInputDocument.setField(IS_SERIES, Boolean.FALSE);
                holdingsSolrInputDocument.setField(IS_ANALYTIC, analyticMap);
            }
        }
        holdingsSolrInputDocumentList.add(holdingsSolrInputDocument);
    }

    private void updateItemDocument(String seriesHoldingsId, List<String> itemIds, List<SolrInputDocument> solrInputDocumentList, String createOrBreak) {
        for (String itemId : itemIds) {
            SolrInputDocument itemSolrInputDocument = new SolrInputDocument();
            itemSolrInputDocument.addField(AtomicUpdateConstants.UNIQUE_ID, itemId);
            Map holdingsIdsMap = new HashMap();
            if (createOrBreak.equalsIgnoreCase(CREATE_RELATION)) {
                holdingsIdsMap.put(AtomicUpdateConstants.ADD, seriesHoldingsId);
                itemSolrInputDocument.setField(HOLDINGS_IDENTIFIER, holdingsIdsMap);
                itemSolrInputDocument.setField(IS_ANALYTIC, Boolean.TRUE);
                updateHoldingsDocsOfAnalyticItem(seriesHoldingsId, itemId, itemIds, solrInputDocumentList, CREATE_RELATION);
            } else if (createOrBreak.equalsIgnoreCase(BREAK_RELATION)) {
                holdingsIdsMap.put(AtomicUpdateConstants.REMOVE, seriesHoldingsId);
                itemSolrInputDocument.addField(HOLDINGS_IDENTIFIER, holdingsIdsMap);
                itemSolrInputDocument.setField(IS_ANALYTIC, Boolean.FALSE);
                updateHoldingsDocsOfAnalyticItem(seriesHoldingsId, itemId, itemIds, solrInputDocumentList, BREAK_RELATION);
            }
            solrInputDocumentList.add(itemSolrInputDocument);
        }
    }

    private void updateHoldingsDocsOfAnalyticItem(String seriesHoldingsId, String itemId, List<String> itemIds, List<SolrInputDocument> solrInputDocumentList, String createOrBreak) {
        List<String> holdingsIdentifierList = null;
        SolrDocument itemSolrDocument = getSolrDocumentByUUID(itemId);
        Object holdingsIdentifier = itemSolrDocument.getFieldValue(HOLDINGS_IDENTIFIER);
        if (holdingsIdentifier instanceof List) {
            holdingsIdentifierList = (List<String>) holdingsIdentifier;
        } else if (holdingsIdentifier instanceof String) {
            holdingsIdentifierList = new ArrayList<String>();
            holdingsIdentifierList.add((String) holdingsIdentifier);
        }
        Map analyticMap = new HashMap();
        if (CollectionUtils.isNotEmpty(holdingsIdentifierList)) {
            for (String holdingsId : holdingsIdentifierList) {
                if (!holdingsId.equalsIgnoreCase(seriesHoldingsId)) {
                    if (createOrBreak.equalsIgnoreCase(CREATE_RELATION)) {
                        analyticMap.put(AtomicUpdateConstants.SET, Boolean.TRUE);
                        SolrInputDocument holdingsSolrInputDocument = new SolrInputDocument();
                        holdingsSolrInputDocument.addField(AtomicUpdateConstants.UNIQUE_ID, holdingsId);
                        holdingsSolrInputDocument.addField(IS_ANALYTIC, analyticMap);
                        solrInputDocumentList.add(holdingsSolrInputDocument);
                    } else if (createOrBreak.equalsIgnoreCase(BREAK_RELATION)) {
                        if (!hasAnalyticItemInHoldings(holdingsId, itemIds)) {
                            analyticMap.put(AtomicUpdateConstants.SET, null);
                            SolrInputDocument holdingsSolrInputDocument = new SolrInputDocument();
                            holdingsSolrInputDocument.addField(AtomicUpdateConstants.UNIQUE_ID, holdingsId);
                            holdingsSolrInputDocument.addField(IS_ANALYTIC, analyticMap);
                            solrInputDocumentList.add(holdingsSolrInputDocument);
                        }
                    }
                }
            }
        }
    }

    private Boolean hasAnalyticItemInHoldings(String holdingsId, List<String> itemIds) {
        SolrDocument holdingsSolrDocument = getSolrDocumentByUUID(holdingsId);
        List<String> itemIdentifierList = null;
        Object itemIdentifier = holdingsSolrDocument.getFieldValue(ITEM_IDENTIFIER);
        if (itemIdentifier instanceof List) {
            itemIdentifierList = (List<String>) itemIdentifier;
        } else if (itemIdentifier instanceof String) {
            itemIdentifierList = new ArrayList<String>();
            itemIdentifierList.add((String) itemIdentifier);
        }
        boolean hasAnalytic = false;
        for (String itemId : itemIdentifierList) {
            if (!itemIds.contains(itemId)) {
                SolrDocument itemSolrDocument = getSolrDocumentByUUID(itemId);
                if (itemSolrDocument.getFieldValue(IS_ANALYTIC) instanceof Boolean) {
                    hasAnalytic = (Boolean) itemSolrDocument.getFieldValue(IS_ANALYTIC);
                    if (hasAnalytic) {
                        break;
                    }
                }
            }
        }
        return hasAnalytic;
    }


    public void unbindOne(List<String> holdingsIds, String bibId) throws SolrServerException, IOException {
        List<SolrInputDocument> solrInputDocumentList = new ArrayList<>();
        updateBibToUnbindOneBib(holdingsIds, bibId, solrInputDocumentList);
        SolrServer server = SolrServerManager.getInstance().getSolrServer();
        server.add(solrInputDocumentList);
        server.commit();
    }

    public void unbindAll(List<String> holdingsIds, String bibId) throws SolrServerException, IOException {
        List<SolrInputDocument> solrInputDocumentList = new ArrayList<>();
        updateBibToUnbindAllBib(holdingsIds, bibId, solrInputDocumentList);
        SolrServer server = SolrServerManager.getInstance().getSolrServer();
        server.add(solrInputDocumentList);
        server.commit();
    }

    private void updateBibToUnbindOneBib(List<String> holdingsIds, String bibId, List<SolrInputDocument> solrInputDocumentList) throws SolrServerException {
        SolrDocument bibSolrDocument = getSolrDocumentByUUID(bibId);
        Object object = bibSolrDocument.getFieldValue(HOLDINGS_IDENTIFIER);
        List<String> holdingsIdsSolr = new ArrayList<>();
        if (object instanceof List) {
            holdingsIdsSolr = (List<String>) object;
        } else if (object instanceof String) {
            holdingsIdsSolr.add((String) object);
        }
        for (String holdingsId : holdingsIds) {
            holdingsIdsSolr.remove(holdingsId);
            SolrDocument holdingsSolrDocument = getSolrDocumentByUUID(holdingsId);
            object = holdingsSolrDocument.getFieldValue(BIB_IDENTIFIER);
            List<String> bibIds = new ArrayList<>();
            if (object instanceof List) {
                bibIds = (List<String>) object;

            } else if (object instanceof String) {
                bibIds.add((String) object);
            }
            bibIds.remove(bibId);
            holdingsSolrDocument.setField(BIB_IDENTIFIER, bibIds);
            holdingsSolrDocument.setField("isBoundwith", false);
            solrInputDocumentList.add(buildSolrInputDocFromSolrDoc(holdingsSolrDocument));
            List<String> itemIdentifierList = null;
            Object itemIdentifier = holdingsSolrDocument.getFieldValue(ITEM_IDENTIFIER);
            if (itemIdentifier instanceof List) {
                itemIdentifierList = (List<String>) itemIdentifier;
            } else if (itemIdentifier instanceof String) {
                itemIdentifierList = new ArrayList<String>();
                itemIdentifierList.add((String) itemIdentifier);
            }
            for (String itemId : itemIdentifierList) {
                SolrInputDocument itemSolrInputDocument = new SolrInputDocument();
                itemSolrInputDocument.addField(AtomicUpdateConstants.UNIQUE_ID, itemId);
                itemSolrInputDocument.setField(BIB_ID, bibIds);
                solrInputDocumentList.add(itemSolrInputDocument);
            }
        }
        bibSolrDocument.setField(HOLDINGS_IDENTIFIER, holdingsIdsSolr);
        solrInputDocumentList.add(buildSolrInputDocFromSolrDoc(bibSolrDocument));
    }

    private void updateBibToUnbindAllBib(List<String> holdingsIds, String bibId, List<SolrInputDocument> solrInputDocumentList) throws SolrServerException {
        Object object;
        Set<String> removedBibIdList = new HashSet<>();
        for (String holdingsId : holdingsIds) {
            SolrDocument holdingsSolrDocument = getSolrDocumentByUUID(holdingsId);
            object = holdingsSolrDocument.getFieldValue(BIB_IDENTIFIER);
            List<String> bidIds = new ArrayList<>();
            Set<String> bidIdsToAdd = new HashSet<>();
            if (object instanceof List) {
                bidIds = (List<String>) object;
            } else if (object instanceof String) {
                bidIds.add((String) object);
            }
            bidIdsToAdd.addAll(bidIds);
            for (String bibIdToRemove : bidIds) {
                if (!bibIdToRemove.equals(bibId)) {
                    bidIdsToAdd.remove(bibIdToRemove);
                    removedBibIdList.add(bibIdToRemove);
                }
            }
            List<String> itemIdentifierList = null;
            Object itemIdentifier = holdingsSolrDocument.getFieldValue(ITEM_IDENTIFIER);
            if (itemIdentifier instanceof List) {
                itemIdentifierList = (List<String>) itemIdentifier;
            } else if (itemIdentifier instanceof String) {
                itemIdentifierList = new ArrayList<String>();
                itemIdentifierList.add((String) itemIdentifier);
            }
            for (String itemId : itemIdentifierList) {
                SolrInputDocument itemSolrInputDocument = new SolrInputDocument();
                itemSolrInputDocument.addField(AtomicUpdateConstants.UNIQUE_ID, itemId);
                itemSolrInputDocument.setField(BIB_ID, bidIdsToAdd);
                solrInputDocumentList.add(itemSolrInputDocument);
            }
            holdingsSolrDocument.setField(BIB_IDENTIFIER, bidIdsToAdd);
            holdingsSolrDocument.setField("isBoundwith", false);
            solrInputDocumentList.add(buildSolrInputDocFromSolrDoc(holdingsSolrDocument));
        }
        for (String removedBibId : removedBibIdList) {
            SolrDocument bibsSolrDocument = getSolrDocumentByUUID(removedBibId);
            object = bibsSolrDocument.getFieldValue(HOLDINGS_IDENTIFIER);
            List<String> holdingsIdList = new ArrayList<>();
            Set<String> holdingsIdSet = new HashSet<>();
            if (object instanceof List) {
                holdingsIdList = (List<String>) object;
            } else if (object instanceof String) {
                holdingsIdList.add((String) object);
            }
            holdingsIdSet.addAll(holdingsIdList);
            for (String holdingsId : holdingsIds) {
                holdingsIdList.remove(holdingsId);
            }
            bibsSolrDocument.setField(HOLDINGS_IDENTIFIER, holdingsIdList);
            solrInputDocumentList.add(buildSolrInputDocFromSolrDoc(bibsSolrDocument));
        }
    }


}