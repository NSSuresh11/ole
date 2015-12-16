package org.kuali.ole.spring.batch.processor;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.codehaus.jettison.json.JSONException;
import org.kuali.incubator.SolrRequestReponseHandler;
import org.kuali.ole.converter.MarcXMLConverter;
import org.kuali.ole.oleng.batch.profile.model.BatchProcessProfile;
import org.kuali.ole.oleng.batch.profile.model.BatchProfileMatchPoint;
import org.kuali.ole.spring.batch.BatchUtil;
import org.kuali.rice.krad.UserSession;
import org.kuali.rice.krad.util.GlobalVariables;
import org.marc4j.MarcWriter;
import org.marc4j.MarcXmlWriter;
import org.marc4j.marc.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by pvsubrah on 12/7/15.
 */
public abstract class BatchFileProcessor extends BatchUtil {


    private static final Logger LOG = LoggerFactory.getLogger(BatchFileProcessor.class);
    private MarcXMLConverter marcXMLConverter;
    private SolrRequestReponseHandler solrRequestReponseHandler;

    public void processBatch(String  rawMarc, String profileName) {
        try {
            List<Record> records = getMarcXMLConverter().convertRawMarchToMarc(rawMarc);

            BatchProcessProfile batchProcessProfile = fetchBatchProcessProfile(profileName);

            String responseData = processRecords(records, batchProcessProfile);
            LOG.info("Response Data : " + responseData);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private BatchProcessProfile fetchBatchProcessProfile(String profileName) {
        //TODO : Need to fetch profile from database. As of now its has been hardcoded.

        BatchProcessProfile batchProcessProfile = new BatchProcessProfile();
        batchProcessProfile.setBatchProcessProfileName(profileName);
        List<BatchProfileMatchPoint> batchProfileMatchPoints = new ArrayList<>();

        BatchProfileMatchPoint profileMatchPoint1 = new BatchProfileMatchPoint();
        profileMatchPoint1.setMatchPoint("980 $a");
        batchProfileMatchPoints.add(profileMatchPoint1);

        if (batchProcessProfile.getBatchProcessProfileName().equalsIgnoreCase("BibForInvoiceYBP")) {
            BatchProfileMatchPoint profileMatchPoint2 = new BatchProfileMatchPoint();
            profileMatchPoint2.setMatchPoint("935 $a");
            batchProfileMatchPoints.add(profileMatchPoint2);
        }
        batchProcessProfile.setBatchProfileMatchPointList(batchProfileMatchPoints);

        return batchProcessProfile;
    }

    public abstract String processRecords(List<Record> records, BatchProcessProfile batchProcessProfile) throws JSONException;

    public String getUpdatedUserName() {
        UserSession userSession = GlobalVariables.getUserSession();
        if(null != userSession) {
            return userSession.getPrincipalName();
        }
        return null;
    }

    public MarcXMLConverter getMarcXMLConverter() {
        if(null == marcXMLConverter) {
            marcXMLConverter = new MarcXMLConverter();
        }
        return marcXMLConverter;
    }

    public void setMarcXMLConverter(MarcXMLConverter marcXMLConverter) {
        this.marcXMLConverter = marcXMLConverter;
    }

    @Override
    public SolrRequestReponseHandler getSolrRequestReponseHandler() {
        if(null == solrRequestReponseHandler) {
            solrRequestReponseHandler = new SolrRequestReponseHandler();
        }
        return solrRequestReponseHandler;
    }

    @Override
    public void setSolrRequestReponseHandler(SolrRequestReponseHandler solrRequestReponseHandler) {
        this.solrRequestReponseHandler = solrRequestReponseHandler;
    }
}
