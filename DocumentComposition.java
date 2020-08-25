package com.dianju.signatureServer;

import com.artofsolving.jodconverter.DefaultDocumentFormatRegistry;
import com.artofsolving.jodconverter.DocumentConverter;
import com.artofsolving.jodconverter.DocumentFormat;
import com.artofsolving.jodconverter.openoffice.connection.OpenOfficeConnection;
import com.artofsolving.jodconverter.openoffice.connection.SocketOpenOfficeConnection;
import com.artofsolving.jodconverter.openoffice.converter.OpenOfficeDocumentConverter;
/*import com.aspose.words.FontSettings;
import com.aspose.words.IWarningCallback;
import com.aspose.words.WarningInfo;
import com.aspose.words.WarningType;*/
import com.dianju.core.LicenseConfig;
import com.dianju.core.Util;
import com.dianju.core.ZipUtil;
import com.dianju.core.models.UUIDReduce;
import com.dianju.modules.document.models.DocumentDao;
import com.dianju.modules.gjzwInterface.DJException;
import com.dianju.modules.log.models.LogFileServerSeal;
import com.dianju.modules.log.models.LogFileServerSealDao;
import com.dianju.modules.log.models.LogServerSeal;
import com.dianju.modules.log.models.LogServerSealDao;
import com.dianju.modules.seal.models.SealDao;
import com.dianju.signatureServer.SignatureFileUploadAndDownLoad.Pattern;
import com.dianju.signatureServer.check.FileToPictureCheck;
import com.dianju.signatureServer.check.PdfVarifyCheck;
import com.dianju.signatureServer.check.SealAutoPdfCheck;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import srvSeal.SrvSealUtil;
import sun.misc.BASE64Decoder;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.io.*;
import java.net.ConnectException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Base64.Decoder;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DocumentComposition {

    private final Logger log = LoggerFactory.getLogger(this.getClass());

    /**
     * 合成模式AddSeal盖章 NoSeal不盖章
     */
    public enum SyntheticPattern {
        AddSeal, NoSeal
    }

    /**
     * 文档合成接口
     *
     * @param xmlStr           请求报文
     * @param syntheticPattern 类型
     * @param beginTime        开始时间
     * @param request
     * @return 响应报文
     */
    public String sealAutoPdf(String xmlStr, SyntheticPattern syntheticPattern, long beginTime, HttpServletRequest request) {
        try {
            //创建所有服务端签章需要的文件夹(如果没有则创建)
            String upload_path = Util.getSystemDictionary("upload_path")+"/";
            String downPathFtp = Util.getSystemDictionary("downPathFtp");
            String downPathHttp = Util.getSystemDictionary("downPathHttp");
            String filePath = Util.getSystemDictionary("filePath");
            String sealFilePath = Util.getSystemDictionary("sealFilePath");
            String templateSynthesis = Util.getSystemDictionary("templateSynthesis");
            Util.createDires(upload_path+downPathFtp);
            Util.createDires(upload_path+downPathHttp);
            Util.createDires(upload_path+filePath);
            Util.createDires(upload_path+sealFilePath);
            Util.createDires(upload_path+templateSynthesis);

            Document doc = DocumentHelper.parseText(xmlStr);
            Element sealDocRequest = doc.getRootElement();
            SealAutoPdfCheck check = new SealAutoPdfCheck(beginTime + "", syntheticPattern);
            LogServerSeal logServerSeal = new LogServerSeal();
            logServerSeal.setRequestXml(xmlStr);
            // logServerSeal.setCreatedAt(Util.getTimeStampOfNow());
            logServerSealDao.save(logServerSeal);
            String returnXml = null;
            //XML格式与内容判断
            if (!check.sealAutoPdf(sealDocRequest, request)) {
                log.info(beginTime + ":xml格式判断:" + check.getError());
                logServerSeal.setResult(0);
                returnXml = getReturnXml(null, "", beginTime,syntheticPattern, check.getError());
            } else {
                log.info(beginTime + ":xml格式判断:成功");
                logServerSeal.setResult(1);

                documentInfo.put("sysId",check.params.get("SYS_ID"));//存放系统id
                documentInfo.put("sourceType","3");//服务端合成
                if(SyntheticPattern.AddSeal == syntheticPattern){//签章
                    documentInfo.put("sourceType","2");
                }
                Element META_DATA = sealDocRequest.element("META_DATA");
                String IS_MERGER = META_DATA.elementText("IS_MERGER");
                Map sealAutoPdfForMerageRet = new HashMap();
                Element RET_FILE_TYPE=META_DATA.element("RET_FILE_TYPE");

                if ("true".equals(IS_MERGER)) {
                    sealAutoPdfForMerageRet = sealAutoPdfForMerage(sealDocRequest, syntheticPattern, beginTime + "");
                    if(RET_FILE_TYPE!=null){
                        sealAutoPdfForMerageRet.put("RET_FILE_TYPE",META_DATA.elementText("RET_FILE_TYPE"));
                    }
                    returnXml = getReturnXml(sealAutoPdfForMerageRet, "sealFilePath", beginTime,syntheticPattern);
                } else {
                    sealAutoPdfForMerageRet = sealAutoPdfForNotMerge(sealDocRequest, syntheticPattern, beginTime + "");
                    if(RET_FILE_TYPE!=null){
                        sealAutoPdfForMerageRet.put("RET_FILE_TYPE",META_DATA.elementText("RET_FILE_TYPE"));
                    }
                    returnXml = getReturnXml(sealAutoPdfForMerageRet, "sealFilePath", beginTime,syntheticPattern);
                }


                //记录日志
                Map<String, String> map = check.params;
                LogFileServerSeal logFileServerSeal = new LogFileServerSeal();
                logFileServerSeal.setSystemId(map.get("SYS_ID"));
                logFileServerSeal.setIpAddress(request.getRemoteAddr());
                logFileServerSeal.setLogServerSealId(logServerSeal.getId());
                logFileServerSeal.setDescription("");
                Iterator iterator = sealAutoPdfForMerageRet.values().iterator();
                while (iterator.hasNext()) {
                    Map m = (Map) iterator.next();
                    //logFileServerSeal.setId(null);
                    logFileServerSeal.setResult(Integer.parseInt(m.get("RET_CODE") + ""));
                    logFileServerSeal.setDocumentName((String) m.get("FILE_NO"));
                    // logFileServerSeal.setCreatedAt(Util.getTimeStampOfNow());
                    logFileServerSealDao.save(logFileServerSeal);
                }
            }
            logServerSeal.setResponseXml(returnXml);
            logServerSealDao.save(logServerSeal);

            return returnXml;
        } catch (DocumentException e) {
            log.info(beginTime + "");
            e.printStackTrace();
            return getReturnXml(null, "", beginTime,syntheticPattern, "xml解析失败");
        } catch (Exception e) {
            log.info(beginTime + "");
            e.printStackTrace();
            return getReturnXml(null, "", beginTime,syntheticPattern, "模版合成与盖章失败");
        }
    }

    public String sealAutoAip(String xmlStr, SyntheticPattern syntheticPattern, long beginTime, HttpServletRequest request) {
        try {
            //创建所有服务端签章需要的文件夹(如果没有则创建)
            String upload_path = Util.getSystemDictionary("upload_path")+"/";
            String downPathFtp = Util.getSystemDictionary("downPathFtp");
            String downPathHttp = Util.getSystemDictionary("downPathHttp");
            String filePath = Util.getSystemDictionary("filePath");
            String sealFilePath = Util.getSystemDictionary("sealFilePath");
            String templateSynthesis = Util.getSystemDictionary("templateSynthesis");
            Util.createDires(upload_path+downPathFtp);
            Util.createDires(upload_path+downPathHttp);
            Util.createDires(upload_path+filePath);
            Util.createDires(upload_path+sealFilePath);
            Util.createDires(upload_path+templateSynthesis);

            Document doc = DocumentHelper.parseText(xmlStr);
            Element sealDocRequest = doc.getRootElement();
            SealAutoPdfCheck check = new SealAutoPdfCheck(beginTime + "", syntheticPattern);
            LogServerSeal logServerSeal = new LogServerSeal();
            logServerSeal.setRequestXml(xmlStr);
            // logServerSeal.setCreatedAt(Util.getTimeStampOfNow());
            logServerSealDao.save(logServerSeal);
            String returnXml = null;
            //XML格式与内容判断
            if (!check.sealAutoPdf(sealDocRequest, request)) {
                log.info(beginTime + ":xml格式判断:" + check.getError());
                logServerSeal.setResult(0);
                returnXml = getReturnXml(null, "", beginTime,syntheticPattern, check.getError());
            } else {
                log.info(beginTime + ":xml格式判断:成功");
                logServerSeal.setResult(1);

                documentInfo.put("sysId",check.params.get("SYS_ID"));//存放系统id
                documentInfo.put("sourceType","3");//服务端合成
                if(SyntheticPattern.AddSeal == syntheticPattern){//签章
                    documentInfo.put("sourceType","2");
                }

                Element META_DATA = sealDocRequest.element("META_DATA");
                String IS_MERGER = META_DATA.elementText("IS_MERGER");
                Map sealAutoAipForMerageRet = new HashMap();
                if ("true".equals(IS_MERGER)) {
                    sealAutoAipForMerageRet = sealAutoAipForMerage(sealDocRequest, syntheticPattern, beginTime + "");
                    returnXml = getReturnXml(sealAutoAipForMerageRet, "sealFilePath", beginTime,syntheticPattern);
                } else {
                    sealAutoAipForMerageRet = sealAutoAipForNotMerge(sealDocRequest, syntheticPattern, beginTime + "");
                    returnXml = getReturnXml(sealAutoAipForMerageRet, "sealFilePath", beginTime,syntheticPattern);
                }


                //记录日志
                Map<String, String> map = check.params;
                LogFileServerSeal logFileServerSeal = new LogFileServerSeal();
                logFileServerSeal.setSystemId(map.get("SYS_ID"));
                logFileServerSeal.setIpAddress(request.getRemoteAddr());
                logFileServerSeal.setLogServerSealId(logServerSeal.getId());
                logFileServerSeal.setDescription("");
                Iterator iterator = sealAutoAipForMerageRet.values().iterator();
                while (iterator.hasNext()) {
                    Map m = (Map) iterator.next();
                    //logFileServerSeal.setId(null);
                    logFileServerSeal.setResult(Integer.parseInt(m.get("RET_CODE") + ""));
                    logFileServerSeal.setDocumentName((String) m.get("FILE_NO"));
                    // logFileServerSeal.setCreatedAt(Util.getTimeStampOfNow());
                    logFileServerSealDao.save(logFileServerSeal);
                }
            }
            logServerSeal.setResponseXml(returnXml);
            logServerSealDao.save(logServerSeal);

            return returnXml;
        } catch (DocumentException e) {
            log.info(beginTime + "");
            e.printStackTrace();
            return getReturnXml(null, "", beginTime,syntheticPattern, "xml解析失败");
        } catch (Exception e) {
            log.info(beginTime + "");
            e.printStackTrace();
            return getReturnXml(null, "", beginTime,syntheticPattern, "模版合成与盖章失败");
        }
    }

    public void init() {
        if (srvSealUtil == null) {
            srvSealUtil = (SrvSealUtil) Util.getBean("srvSealUtil");
        }
        if (syntheticType == null) {
            path = Util.getSystemDictionary("upload_path");
            filePath = path + "/filePath";
            sealFilePath = path + "/sealFilePath";
            syntheticType = Util.getSystemDictionary("synthetic_type");
            fileToPicture=path+"/fileToPicture/";

        }
    }

    /**
     * 文档合成（合并）
     *
     * @param sealDocRequest
     * @param syntheticPattern
     * @param beginTime
     * @return
     */
    private Map sealAutoPdfForMerage(Element sealDocRequest, SyntheticPattern syntheticPattern, String beginTime) throws Exception {
        Map retMap = new HashMap();
        Element META_DATA = sealDocRequest.element("META_DATA");
        retMap.put("FILE_NO", META_DATA.elementText("FILE_NO"));
        // try {
        DocumentCreating documentCreating = (DocumentCreating) Util.getBean("documentCreating");
        Element FILE_LIST = sealDocRequest.element("FILE_LIST");
        List<Element> TREE_NODES = FILE_LIST.elements("TREE_NODE");
        Map downFileMap = new HashMap<Integer, String>();
        String fileDownRet = SignatureFileUploadAndDownLoad.downFile(TREE_NODES, beginTime, downFileMap, Pattern.End);
        if (!"ok".equals(fileDownRet)) {
            throw new Exception(fileDownRet);
        }

        init();//初始化控件
        String savePath = filePath + "/" + beginTime + "." + syntheticType;
        int nObjID = documentCreating.openObj("", 0, 0);
        log.info(beginTime + ":nObjID:" + nObjID);
        try {
            if (nObjID <= 0) {
                log.info(beginTime + ":服务器繁忙，请稍后重试1");
                throw new Exception("服务器繁忙，请稍后重试1");
            }
            int l = documentCreating.login(nObjID, 2, "HWSEALDEMOXX", "");
            log.info(beginTime + ":login:" + l);
            if (l != 0) {
                throw new Exception("未授权的服务器");
            }
            String makeMergerFileret = documentCreating.makeMergerFile(nObjID, FILE_LIST, downFileMap, beginTime);
            if (!"ok".equals(makeMergerFileret)) {
                retMap.put("FILE_MSG", makeMergerFileret);
                throw new Exception(makeMergerFileret);
            }
            String insertCodeBarret = documentCreating.insertCodeBar(nObjID, META_DATA, beginTime);
            if (!"ok".equals(insertCodeBarret)) {
                throw new Exception(insertCodeBarret);
            }
            int saveFileRet = documentCreating.saveFile(nObjID, savePath, syntheticType, 1);
            log.info(beginTime + ":saveFile:" + saveFileRet);
            if (saveFileRet == 0) {
                log.info(beginTime + ":saveFile文档保存失败，请检查服务器，保存路径：" + savePath);
                throw new Exception("saveFile文档保存失败");
            } else {
                log.info(beginTime + ":saveFile文档保存成功" + new Date() + "保存路径：" + savePath);
            }
        } catch (Exception e) {
            throw e;
        } finally {
            log.info("saveFile文档关闭");
            documentCreating.saveFile(nObjID, "", syntheticType, 0);
        }

          /*  nObjID = documentCreating.openObj(savePath, 0, 0);
            log.info(beginTime + ":nObjID:" + nObjID);
            if (nObjID <= 0) {
                log.info(beginTime + ":服务器繁忙，请稍后重试4");
                throw new Exception("服务器繁忙，请稍后重试4");
            }*/
        try {
            //  int l = documentCreating.login(nObjID, 2, "HWSEALDEMOXX", "");
            //  log.info(beginTime + ":login:" + l);
            String addSealret = documentCreating.addSeal(savePath, syntheticPattern, META_DATA, beginTime);
            if (!"ok".equals(addSealret)) {
                throw new Exception(addSealret);
            }else{

                String creator = documentInfo.get("sysId");
                String creatorName = documentInfo.get("sysId");
                byte sourceType = Byte.parseByte(documentInfo.get("sourceType"));
                String filepath = sealFilePath +"/"+retMap.get("FILE_NO");
                //文件信息保存
                boolean saveInfo = this.saveServerDocument(retMap.get("FILE_NO")+"",creator,creatorName,filepath,sourceType);
                if (!saveInfo){
                    log.info("向document表汇中插入文档信息失败");
                    throw new Exception("向document表汇中插入文档信息失败");
                }

                if (META_DATA.elementText("FTP_SAVEPATH") != null && !"".equals(META_DATA.elementText("FTP_SAVEPATH"))) {
                    String ftpUpFileRet = SignatureFileUploadAndDownLoad.ftpUpFile(META_DATA, sealFilePath + "/" + META_DATA.elementText("FILE_NO"), beginTime);
                    if ("ok".equals(ftpUpFileRet)) {
                        retMap.put("RET_CODE", "1");
                        retMap.put("FILE_MSG", "文档上传成功");
                    } else {
                        retMap.put("RET_CODE", "0");
                        retMap.put("FILE_MSG", ftpUpFileRet);
                    }

                } else {
                    retMap.put("RET_CODE", "1");
                    retMap.put("FILE_MSG", "文档合成成功");
                }
            }



        } catch (Exception e) {
            e.printStackTrace();
            retMap.put("RET_CODE", "0");
            retMap.put("FILE_MSG", e.getMessage());
        }
        Map m = new HashMap();
        m.put(0, retMap);
        return m;
    }

    private Map sealAutoAipForMerage(Element sealDocRequest, SyntheticPattern syntheticPattern, String beginTime) throws Exception {
        Map retMap = new HashMap();
        Element META_DATA = sealDocRequest.element("META_DATA");
        retMap.put("FILE_NO", META_DATA.elementText("FILE_NO"));
        // try {
        DocumentCreating documentCreating = (DocumentCreating) Util.getBean("documentCreating");
        Element FILE_LIST = sealDocRequest.element("FILE_LIST");
        List<Element> TREE_NODES = FILE_LIST.elements("TREE_NODE");
        Map downFileMap = new HashMap<Integer, String>();
        String fileDownRet = SignatureFileUploadAndDownLoad.downFile(TREE_NODES, beginTime, downFileMap, Pattern.End);
        if (!"ok".equals(fileDownRet)) {
            throw new Exception(fileDownRet);
        }

        init();//初始化控件
        String savePath = filePath + "/" + beginTime + "." + syntheticType;
        int nObjID = documentCreating.openObj("", 0, 0);
        log.info(beginTime + ":nObjID:" + nObjID);
        try {
            if (nObjID <= 0) {
                log.info(beginTime + ":服务器繁忙，请稍后重试1");
                throw new Exception("服务器繁忙，请稍后重试1");
            }
            int l = documentCreating.login(nObjID, 2, "HWSEALDEMOXX", "");
            log.info(beginTime + ":login:" + l);
            if (l != 0) {
                throw new Exception("未授权的服务器");
            }
            String makeMergerFileret = documentCreating.makeMergerFile(nObjID, FILE_LIST, downFileMap, beginTime);
            if (!"ok".equals(makeMergerFileret)) {
                retMap.put("FILE_MSG", makeMergerFileret);
                throw new Exception(makeMergerFileret);
            }
            String insertCodeBarret = documentCreating.insertCodeBar(nObjID, META_DATA, beginTime);
            if (!"ok".equals(insertCodeBarret)) {
                throw new Exception(insertCodeBarret);
            }
            int saveFileRet = documentCreating.saveFile(nObjID, savePath, syntheticType, 1);
            log.info(beginTime + ":saveFile:" + saveFileRet);
            if (saveFileRet == 0) {
                log.info(beginTime + ":saveFile文档保存失败，请检查服务器，保存路径：" + savePath);
                throw new Exception("saveFile文档保存失败");
            } else {
                log.info(beginTime + ":saveFile文档保存成功" + new Date() + "保存路径：" + savePath);
            }
        } catch (Exception e) {
            throw e;
        } finally {
            log.info("saveFile文档关闭");
            documentCreating.saveFile(nObjID, "", syntheticType, 0);
        }

          /*  nObjID = documentCreating.openObj(savePath, 0, 0);
            log.info(beginTime + ":nObjID:" + nObjID);
            if (nObjID <= 0) {
                log.info(beginTime + ":服务器繁忙，请稍后重试4");
                throw new Exception("服务器繁忙，请稍后重试4");
            }*/
        try {
            //  int l = documentCreating.login(nObjID, 2, "HWSEALDEMOXX", "");
            //  log.info(beginTime + ":login:" + l);
            String addSealret = documentCreating.addSeal(savePath, syntheticPattern, META_DATA, beginTime);
            if (!"ok".equals(addSealret)) {
                throw new Exception(addSealret);
            }else{

                String creator = documentInfo.get("sysId");
                String creatorName = documentInfo.get("sysId");
                byte sourceType = Byte.parseByte(documentInfo.get("sourceType"));
                String filepath = sealFilePath +"/"+retMap.get("FILE_NO");
                //文件信息保存
                boolean saveInfo = this.saveServerDocument(retMap.get("FILE_NO")+"",creator,creatorName,filepath,sourceType);
                if (!saveInfo){
                    log.info("向document表汇中插入文档信息失败");
                    throw new Exception("向document表汇中插入文档信息失败");
                }

                if (META_DATA.elementText("FTP_SAVEPATH") != null && !"".equals(META_DATA.elementText("FTP_SAVEPATH"))) {
                    String ftpUpFileRet = SignatureFileUploadAndDownLoad.ftpUpFile(META_DATA, sealFilePath + "/" + META_DATA.elementText("FILE_NO"), beginTime);
                    if ("ok".equals(ftpUpFileRet)) {
                        retMap.put("RET_CODE", "1");
                        retMap.put("FILE_MSG", "文档上传成功");
                    } else {
                        retMap.put("RET_CODE", "0");
                        retMap.put("FILE_MSG", ftpUpFileRet);
                    }

                } else {
                    retMap.put("RET_CODE", "1");
                    retMap.put("FILE_MSG", "文档合成成功");
                }
            }



        } catch (Exception e) {
            e.printStackTrace();
            retMap.put("RET_CODE", "0");
            retMap.put("FILE_MSG", e.getMessage());
        }
        Map m = new HashMap();
        m.put(0, retMap);
        return m;
    }

    /**
     * 文档合成不合并
     *
     * @param sealDocRequest
     * @param syntheticPattern
     * @param beginTime
     * @return
     */
    private Map sealAutoPdfForNotMerge(Element sealDocRequest, SyntheticPattern syntheticPattern, String beginTime) throws Exception {
        init();//初始化控件
        DocumentCreating documentCreating = (DocumentCreating) Util.getBean("documentCreating");
        Element FILE_LIST = sealDocRequest.element("FILE_LIST");
        List<Element> TREE_NODES = FILE_LIST.elements("TREE_NODE");
        Map msgMap = new HashMap<Integer, Map<String, String>>();
        SignatureFileUploadAndDownLoad.downFile(TREE_NODES, beginTime, msgMap, Pattern.Next);
        documentCreating.templateSynthesis(TREE_NODES, beginTime + "", msgMap);
        for (int i = 0; i < TREE_NODES.size(); i++) {
            log.info("进入循环---------");
            Map thisMsg = (Map) msgMap.get(i);
            Element TREE_NODE = TREE_NODES.get(i);
            thisMsg.put("FILE_NO", TREE_NODE.elementText("FILE_NO"));
            if ("1".equals(thisMsg.get("RET_CODE") + "")) {
                log.info("进入循环2------");
                String filePath = (String) thisMsg.get("FILE_MSG");
                //对filepath进行处理，非pdf文件转换为pdf文件
                int len = filePath.lastIndexOf(".");
                String fileSuffix = filePath.substring(len);
                String newFilePath = filePath.substring(0, len)+".pdf";
                log.info("日志1---------");
                //if (fileSuffix.equals(".doc") || fileSuffix.equals(".docx") || fileSuffix.equals(".xls")|| fileSuffix.equals(".xlsx") || fileSuffix.equals(".ppt") || fileSuffix.equals(".pptx")) {
                if (fileSuffix.equals(".xls")|| fileSuffix.equals(".xlsx") || fileSuffix.equals(".ppt") || fileSuffix.equals(".pptx")) {
                    log.info("日志2-------");
                    int otp = srvSealUtil.officeToPdf(-1, filePath, newFilePath);
                    log.info("日志3--------");
                    log.info("opt："+otp);
                    System.out.println("转化文档(1为成功)："+otp);
                    if (otp < 1) {
                        try {
                            throw new Exception("文件转换异常");
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }else if (fileSuffix.equals(".aip")||fileSuffix.equals(".txt")||fileSuffix.equals(".bmp")) {
                    int nObjID = srvSealUtil.openObj(filePath, 0, 0);
                    System.out.println("nObjID：" + nObjID);
                    if(nObjID<=0){
                        try {
                            throw new Exception("文件转换异常");
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    int l = srvSealUtil.login(nObjID, 4, "HWSEALDEMOXX","DEMO");
                    System.out.println("login:" + l);
                    if(l!=0){
                        try {
                            throw new Exception("文件转换异常");
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    log.info("newFilePath1:"+newFilePath);
                    int save = srvSealUtil.saveFile(nObjID, newFilePath, "pdf",0);
                    log.info("打开newFilePath返回值："+save);
                    System.out.println("save(1为成功)：" + save);
                    if(save!=1){
                        try {
                            throw new Exception("文件转换异常");
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }else if(fileSuffix.equals(".doc") || fileSuffix.equals(".docx")){//wordtoPDF
                    //wordToPdf(TREE_NODES.get(i),filePath,newFilePath);
                    //wordToPDF---->begin
                    /*File xlsf = new File(filePath);
                    File targetF = new File(filePath.substring(0,filePath.lastIndexOf(".")+1) + "pdf");
                    // 获得文件格式
                    DefaultDocumentFormatRegistry ddfr = new DefaultDocumentFormatRegistry();
                    String wordType = TREE_NODES.get(i).elementText("FILE_PATH").substring(TREE_NODES.get(i).elementText("FILE_PATH").lastIndexOf(".")+1);
                    DocumentFormat docFormat = ddfr.getFormatByFileExtension(wordType);
                    DocumentFormat pdfFormat = ddfr.getFormatByFileExtension("pdf");
                    // stream 流的形式
                    InputStream inputStream = null;
                    OutputStream outputStream = null;
                    try {
                        inputStream = new FileInputStream(xlsf);
                        outputStream = new FileOutputStream(targetF);
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    }
                    OpenOfficeConnection connection = new SocketOpenOfficeConnection(8100);
                    System.out.println(connection);
                    try {
                        connection.connect();
                        DocumentConverter converter = new OpenOfficeDocumentConverter(connection);
                        System.out.println("inputStream------" + inputStream);
                        System.out.println("outputStream------" + outputStream);
                        converter.convert(inputStream, docFormat, outputStream, pdfFormat);
                    } catch (ConnectException e) {
                        e.printStackTrace();
                    } finally {
                        if (connection != null) {
                            connection.disconnect();
                        }
                    }*/
                    //wordToPDF---->end
                    //int nObjID = srvSealUtil.openObj(filePath.substring(0,filePath.lastIndexOf(".")+1) + "pdf", 0, 0);
                    /*try {
                        if(nObjID<=0){
                            try {
                                throw new Exception("文件转换异常");
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                        int l = srvSealUtil.login(nObjID, 2,"HWSEALDEMOXX","");
                        if(l != 0){
                            try {
                                throw new Exception("文件转换异常");
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }*/
                    int otp = srvSealUtil.officeToPdf(-1, filePath, filePath.substring(0,filePath.lastIndexOf(".")+1) + "pdf");
                    if (otp < 1) {
                        try {
                            throw new Exception("文件转换异常");
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    /*} catch (Exception e) {
                        e.printStackTrace();
                    }finally {
                        srvSealUtil.saveFile(nObjID, "", syntheticType, 0);
                    }*/
                }

                String fileNo = (String) thisMsg.get("FILE_NO");
                String savePath = this.filePath + "/" + fileNo ;

                log.info("newFilePath2:"+newFilePath);
                int nObjID = documentCreating.openObj(newFilePath, 0, 0);
                int ret = 1;
                log.info(beginTime + ":nObjID:" + nObjID);
                if (nObjID <= 0) {
                    log.info(beginTime + ":服务器繁忙，请稍后重试3");
                    thisMsg.put("FILE_MSG", "服务器繁忙，请稍后重试3");
                    ret = 0;
                }
                int l = documentCreating.login(nObjID, 2, "HWSEALDEMOXX", "");
                log.info(fileNo + ":login:" + l);
                try {
                    String insertCodeBarret = documentCreating.insertCodeBar(nObjID, TREE_NODE, fileNo);
                    if (!"ok".equals(insertCodeBarret)) {
                        thisMsg.put("RET_CODE", "0");
                        thisMsg.put("FILE_MSG", "添加二维码失败");
                        ret = 0;
                    }
/*
                    String saveCodeBar=savePath.substring(0,savePath.lastIndexOf("/"))+"codeBar/"+new Date().getTime()+savePath.substring(savePath.lastIndexOf("."));
*/
                    int saveFileRet = documentCreating.saveFile(nObjID, savePath, syntheticType, 1);
                    if (saveFileRet == 0) {
                        log.info(beginTime + ":saveFile文档保存失败，请检查服务器，保存路径：" + savePath);
                        throw new Exception("saveFile文档保存失败");
                    } else {
                        log.info(beginTime + ":saveFile文档保存成功" + new Date() + "保存路径：" + savePath);
                    }

                    String insertWaterMaker = documentCreating.insertWatermark(TREE_NODE, fileNo,savePath,savePath, syntheticType);
                    if (!"ok".equals(insertWaterMaker)) {
                        thisMsg.put("RET_CODE", "0");
                        thisMsg.put("FILE_MSG", "添加水印失败");
                        ret = 0;
                    }
/*                    if(!"true".equalsIgnoreCase(TREE_NODE.elementText("IS_WATERMARK"))){
                        savePath=saveCodeBar;
                    }      */

                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                } finally {
                    log.info(fileNo + ":" + i + "文档关闭");
                    documentCreating.saveFile(nObjID, "", syntheticType, 0);
                }
                if (ret == 1) {
                    String addSealret = documentCreating.addSeal(savePath, syntheticPattern, TREE_NODE, fileNo);
                    if (!"ok".equals(addSealret)) {
                        thisMsg.put("RET_CODE", "0");
                        thisMsg.put("FILE_MSG", addSealret);
                        ret = 0;
                    } else {

                        String creator = documentInfo.get("sysId");
                        String creatorName = documentInfo.get("sysId");
                        byte sourceType = Byte.parseByte(documentInfo.get("sourceType"));
                        String filepath = sealFilePath +"/"+fileNo;
                        //文件信息保存
                        boolean saveInfo = this.saveServerDocument(fileNo,creator,creatorName,filepath,sourceType);
                        if (!saveInfo){
                            log.info("向document表汇中插入文档信息失败");
                            throw new Exception("向document表汇中插入文档信息失败");
                        }

                        if (TREE_NODE.elementText("FTP_SAVEPATH") != null && !"".equals(TREE_NODE.elementText("FTP_SAVEPATH"))) {
                            String ftpUpFileRet = SignatureFileUploadAndDownLoad.ftpUpFile(TREE_NODE, sealFilePath + "/" + TREE_NODE.elementText("FILE_NO"), beginTime);
                            if ("ok".equals(ftpUpFileRet)) {
                                thisMsg.put("RET_CODE", "1");
                                thisMsg.put("FILE_MSG", "文档上传成功");
                            } else {
                                thisMsg.put("RET_CODE", "0");
                                thisMsg.put("FILE_MSG", ftpUpFileRet);
                            }

                        } else {
                            thisMsg.put("RET_CODE", "1");
                            thisMsg.put("FILE_MSG", "文档合成成功");
                        }
                    }
                }


            }

        }
        log.info("走完了");
        return msgMap;
    }

    /**
     * word——>pdf 文档类型转换
     * @param xmlStr
     * @param beginTime
     * @param request
     * @return
     */
    public String wordToPdf(String xmlStr,long beginTime, HttpServletRequest request) {
        init();//初始化控件
        //创建所有文档转换需要的文件夹(如果没有则创建)
        String upload_path = Util.getSystemDictionary("upload_path")+"/";
        String downPathHttp = Util.getSystemDictionary("downPathHttp");
        String filePath = Util.getSystemDictionary("filePath");
        Util.createDires(upload_path+downPathHttp);
        Util.createDires(upload_path+filePath);

        try{
            HttpSession session = request.getSession();
            System.out.println(xmlStr);
            xmlStr = xmlStr.replace("&","&amp;");
            Document doc = DocumentHelper.parseText(xmlStr);
            Element wordToPdfRequest = doc.getRootElement();
            String returnXml = null;
            SealAutoPdfCheck check = new SealAutoPdfCheck(beginTime+"");
            Element FILE_LIST = wordToPdfRequest.element("FILE_LIST");
            List<Element> TREE_NODES = FILE_LIST.elements("TREE_NODE");
            DocumentCreating documentCreating = (DocumentCreating) Util.getBean("documentCreating");
            Map msgMap = new HashMap<Integer, Map<String, String>>();
            if (!check.wordToPdf(wordToPdfRequest,request)){
                //xml格式判断失败
                return getReturnXml(null, "", beginTime,null, check.getError());
            }else {
                //1.下载文件
                SignatureFileUploadAndDownLoad.downFile(TREE_NODES, beginTime + "", msgMap, Pattern.Next);
                //2.文件类型转换
                for (int i = 0; i < TREE_NODES.size(); i++) {
                    log.info("进入循环---------");
                    Map thisMsg = (Map) msgMap.get(i);
                    Element TREE_NODE = TREE_NODES.get(i);
                    thisMsg.put("FILE_NO", TREE_NODE.elementText("FILE_NO"));
                    log.info(thisMsg.get("RET_CODE")+"");
                    if ("1".equals(thisMsg.get("RET_CODE") + "")) {//RET_CODE为1  下载成功    为0下载失败
                        log.info("进入循环2------");
                        String filePath1 = (String) thisMsg.get("FILE_MSG");
                        //filePath = (String) thisMsg.get("FILE_MSG");
                        //对filepath进行处理，非pdf文件转换为pdf文件
                        int len = filePath1.lastIndexOf(".");
                        String fileSuffix = filePath1.substring(len);
                        //     String newFilePath = filePath1.substring(0, len) + ".pdf";
                        log.info("日志1---------");
                        String fileNo = (String) thisMsg.get("FILE_NO");
                        log.info("fileNo:" + fileNo);
                        String savePath = this.filePath + "/" + fileNo;
                        long start = System.currentTimeMillis();
                        if (fileSuffix.equals(".doc") || fileSuffix.equals(".docx")) {//wordtoPDF
                            if (System.getProperty("os.name").toUpperCase().indexOf("WINDOWS") != -1) {
                                // 开始时间
                                //window转化
                               /* int otp = srvSealUtil.officeToPdf(-1, filePath1, savePath);
                                if (otp < 1) {
                                    thisMsg.put("FILE_MSG", "officeToPdf失败,"+otp);
                                    return getReturnXml(null, "", beginTime,null, "officeToPdf失败,"+otp);
                                }*/
                                com.aspose.words.Document doc1 = new com.aspose.words.Document(filePath1);
                                doc1.save(savePath, com.aspose.words.SaveFormat.PDF);
                                // 结束时间
                                long end = System.currentTimeMillis();
                                System.out.println("转换成功，用时：" + (end - start) + "ms");
                            }else{
                                //linux转化
                                // wordToPdf(TREE_NODES.get(i), filePath1, savePath);
                                //下面的方法，先getFontsSources，再添加新的folder，再setFontsSources
                               /* ArrayList<FontSourceBase> fontsources = new ArrayList<FontSourceBase>();
                                FontSourceBase[] fsb =  FontSettings.getFontsSources();
                                Collections.addAll(fontsources, fsb);
                                FolderFontSource folderFontSource = new FolderFontSource("C:/FontsFolder", true);
                                fontsources.add(folderFontSource);
                                FontSourceBase[] updateFontSources = (FontSourceBase[])fontsources.toArray(new FontSourceBase[0]);
                                FontSettings.setFontsSources(updateFontSources);*/
                                //缺失的字体使用默认字体替换
                                com.aspose.words.Document doc1 = new com.aspose.words.Document(filePath1);
                                /*if (doc1.getFontSettings() == null) {
                                    doc1.setFontSettings(FontSettings.getDefaultInstance());
                                }
                                doc1.getFontSettings().getSubstitutionSettings().getDefaultFontSubstitution().setDefaultFontName("Arial");
                                //打印waring   1.7 jdk不支持Lambda表达式
                                doc1.setWarningCallback(warningInfo -> {
                                    if(warningInfo.getWarningType()==WarningType.FONT_SUBSTITUTION){
                                        System.out.println("[warning]:字体缺失,"+warningInfo.getDescription());
                                    }
                                });
                                //1.7写法
                                doc1.setWarningCallback(new IWarningCallback(){
                                    @Override
                                    public void warning(WarningInfo warningInfo) {
                                        if(warningInfo.getWarningType()==WarningType.FONT_SUBSTITUTION){
                                            System.out.println("[warning]:字体缺失,"+warningInfo.getDescription());
                                        }
                                    }
                                });*/
                                /*if (info.getWarningType() == com.aspose.words.WarningType.FONT_SUBSTITUTION) {
                                    System.out.println("缺少字体: %s", info.getDescription());
                               }*/
                                doc1.save(savePath, com.aspose.words.SaveFormat.PDF);
                                // 结束时间
                                long end = System.currentTimeMillis();
                                System.out.println("转换成功，用时：" + (end - start) + "ms");
                            }

                        } else {//上传文档类型错误
                            return getReturnXml(null, "", beginTime, null, "转换文件类型错误");
                        }
                        thisMsg.put("FILE_MSG", "文档转换成功");
                        returnXml = getReturnXml(msgMap, "filePath", beginTime, null);

                      /*  log.info("newFilePath2:" + newFilePath);
                        int nObjID = documentCreating.openObj(newFilePath, 0, 0);
                        //int ret = 1;
                        log.info(beginTime + ":nObjID:" + nObjID);
                        if (nObjID <= 0) {
                            thisMsg.put("FILE_MSG", "服务器繁忙，请稍后重试3");
                            return getReturnXml(null, "", beginTime,null, "服务器繁忙，请稍后重试3");
                            //ret = 0;
                        }
                        int l = documentCreating.login(nObjID, 2, "HWSEALDEMOXX", "");
                        log.info(fileNo + ":login:" + l);
                        //保存文档
                        try {
                            int saveFileRet = documentCreating.saveFile(nObjID, savePath, syntheticType, 1);
                            if (saveFileRet == 0) {
                                log.info(beginTime + ":saveFile文档保存失败，请检查服务器，保存路径：" + savePath);
                                //throw new Exception("saveFile文档保存失败");
                                return getReturnXml(null, "", beginTime,null, "saveFile文档保存失败");

                            } else {
                                log.info(beginTime + ":saveFile文档保存成功" + new Date() + "保存路径：" + savePath);
                            }

                        } catch (Exception e) {
                            e.printStackTrace();
                            return getReturnXml(null, "", beginTime, null, "文档保存失败");
                        } finally {
                            log.info(fileNo + ":" + i + "文档关闭");
                            documentCreating.saveFile(nObjID, "", syntheticType, 0);
                            thisMsg.put("FILE_MSG", "文档类型转换成功");
                            returnXml = getReturnXml(msgMap, "filePath", beginTime, null);
                        }*/
                    } else {
                        return getReturnXml(null, "", beginTime, null, "文档下载失败");

                    }

                }
            }
            return returnXml;
        }catch (DocumentException e){
            e.printStackTrace();
            return getReturnXml(null, "", beginTime,null, "xml解析失败");
        }catch (Exception e){
            e.printStackTrace();
            return getReturnXml(null, "", beginTime,null, "文档转换失败");
        }
    }


    private void wordToPdf(Element TREE_NODE,String filePath,String newFilePath){
        File xlsf = new File(filePath);
        File targetF = new File(newFilePath);
        // 获得文件格式
        DefaultDocumentFormatRegistry ddfr = new DefaultDocumentFormatRegistry();
        String wordType = TREE_NODE.elementText("FILE_PATH").substring(TREE_NODE.elementText("FILE_PATH").lastIndexOf(".")+1);
        DocumentFormat docFormat = ddfr.getFormatByFileExtension(wordType);
        DocumentFormat pdfFormat = ddfr.getFormatByFileExtension("pdf");
        // stream 流的形式
        InputStream inputStream = null;
        OutputStream outputStream = null;
        try {
            inputStream = new FileInputStream(xlsf);
            outputStream = new FileOutputStream(targetF);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        OpenOfficeConnection connection = new SocketOpenOfficeConnection(8100);
        System.out.println(connection);
        try {
            connection.connect();
            DocumentConverter converter = new OpenOfficeDocumentConverter(connection);
            System.out.println("inputStream------" + inputStream);
            System.out.println("outputStream------" + outputStream);
            converter.convert(inputStream, docFormat, outputStream, pdfFormat);
        } catch (ConnectException e) {
            e.printStackTrace();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 文档合成不合并
     *
     * @param sealDocRequest
     * @param syntheticPattern
     * @param beginTime
     * @return
     */
    private Map sealAutoAipForNotMerge(Element sealDocRequest, SyntheticPattern syntheticPattern, String beginTime) throws Exception {
        init();//初始化控件
        DocumentCreating documentCreating = (DocumentCreating) Util.getBean("documentCreating");
        Element FILE_LIST = sealDocRequest.element("FILE_LIST");
        List<Element> TREE_NODES = FILE_LIST.elements("TREE_NODE");
        Map msgMap = new HashMap<Integer, Map<String, String>>();
        SignatureFileUploadAndDownLoad.downFile(TREE_NODES, beginTime, msgMap, Pattern.Next);
        documentCreating.templateSynthesis(TREE_NODES, beginTime + "", msgMap);
        for (int i = 0; i < TREE_NODES.size(); i++) {
            log.info("进入循环--------");
            Map thisMsg = (Map) msgMap.get(i);
            Element TREE_NODE = TREE_NODES.get(i);
            thisMsg.put("FILE_NO", TREE_NODE.elementText("FILE_NO"));
            if ("1".equals(thisMsg.get("RET_CODE") + "")) {

                String filePath = (String) thisMsg.get("FILE_MSG");
                //对filepath进行处理，非pdf文件转换为pdf文件
                int len = filePath.lastIndexOf(".");
                String fileSuffix = filePath.substring(len);
                String newFilePath = filePath.substring(0, len)+".aip";

                if (fileSuffix.equals(".doc") || fileSuffix.equals(".docx") || fileSuffix.equals(".xls")|| fileSuffix.equals(".xlsx") || fileSuffix.equals(".ppt") || fileSuffix.equals(".pptx")) {
                    System.out.println("openObj打开之前----------");
                    log.info("openObj打开之前----------");
                    int nObjID = srvSealUtil.openObj(filePath, 0, 0);
                    System.out.println("openObj打开之后----------");
                    log.info("openObj打开之后----------");
                    System.out.println("nObjID：" + nObjID);
                    if(nObjID<=0){
                        try {
                            throw new Exception("文件转换异常");
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    int l = srvSealUtil.login(nObjID, 4, "HWSEALDEMOXX","DEMO");
                    System.out.println("login:" + l);
                    if(l!=0){
                        try {
                            throw new Exception("文件转换异常");
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                    int save = srvSealUtil.saveFile(nObjID, newFilePath, "aip",0);
                    System.out.println("save(1为成功)：" + save);
                    if(save!=1){
                        try {
                            throw new Exception("文件转换异常");
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }

                String fileNo = (String) thisMsg.get("FILE_NO");
                String savePath = this.filePath + "/" + fileNo ;
                int nObjID = documentCreating.openObj(newFilePath, 0, 0);
                int ret = 1;
                log.info(beginTime + ":nObjID:" + nObjID);
                if (nObjID <= 0) {
                    log.info(beginTime + ":服务器繁忙，请稍后重试3");
                    thisMsg.put("FILE_MSG", "服务器繁忙，请稍后重试3");
                    ret = 0;
                }
                int l = documentCreating.login(nObjID, 2, "HWSEALDEMOXX", "");
                log.info(fileNo + ":login:" + l);
                try {
                    String insertCodeBarret = documentCreating.insertCodeBar(nObjID, TREE_NODE, fileNo);
                    if (!"ok".equals(insertCodeBarret)) {
                        thisMsg.put("RET_CODE", "0");
                        thisMsg.put("FILE_MSG", "添加二维码失败");
                        ret = 0;
                    }

                    int saveFileRet = documentCreating.saveFile(nObjID, savePath, syntheticType, 1);
                    if (saveFileRet == 0) {
                        log.info(beginTime + ":saveFile文档保存失败，请检查服务器，保存路径：" + savePath);
                        throw new Exception("saveFile文档保存失败");
                    } else {
                        log.info(beginTime + ":saveFile文档保存成功" + new Date() + "保存路径：" + savePath);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                } finally {
                    log.info(fileNo + ":" + i + "文档关闭");
                    documentCreating.saveFile(nObjID, "", syntheticType, 0);
                }
                if (ret == 1) {
                    String addSealret = documentCreating.addSeal(savePath, syntheticPattern, TREE_NODE, fileNo);
                    if (!"ok".equals(addSealret)) {
                        thisMsg.put("RET_CODE", "0");
                        thisMsg.put("FILE_MSG", addSealret);
                        ret = 0;
                    } else {

                        String creator = documentInfo.get("sysId");
                        String creatorName = documentInfo.get("sysId");
                        byte sourceType = Byte.parseByte(documentInfo.get("sourceType"));
                        String filepath = sealFilePath +"/"+fileNo;
                        //文件信息保存
                        boolean saveInfo = this.saveServerDocument(fileNo,creator,creatorName,filepath,sourceType);
                        if (!saveInfo){
                            log.info("向document表汇中插入文档信息失败");
                            throw new Exception("向document表汇中插入文档信息失败");
                        }

                        if (TREE_NODE.elementText("FTP_SAVEPATH") != null && !"".equals(TREE_NODE.elementText("FTP_SAVEPATH"))) {
                            String ftpUpFileRet = SignatureFileUploadAndDownLoad.ftpUpFile(TREE_NODE, sealFilePath + "/" + TREE_NODE.elementText("FILE_NO"), beginTime);
                            if ("ok".equals(ftpUpFileRet)) {
                                thisMsg.put("RET_CODE", "1");
                                thisMsg.put("FILE_MSG", "文档上传成功");
                            } else {
                                thisMsg.put("RET_CODE", "0");
                                thisMsg.put("FILE_MSG", ftpUpFileRet);
                            }

                        } else {
                            thisMsg.put("RET_CODE", "1");
                            thisMsg.put("FILE_MSG", "文档合成成功");
                        }
                    }
                }


            }

        }
        return msgMap;
    }



  /*  private Map sealAutoPdfForNotMerge(Element sealDocRequest, SyntheticPattern syntheticPattern, String beginTime) throws Exception {
        init();//初始化控件
        DocumentCreating documentCreating = (DocumentCreating) Util.getBean("documentCreating");
        Element FILE_LIST = sealDocRequest.element("FILE_LIST");
        List<Element> TREE_NODES = FILE_LIST.elements("TREE_NODE");
        Map msgMap = new HashMap<Integer, Map<String, String>>();
        SignatureFileUploadAndDownLoad.downFile(TREE_NODES, beginTime, msgMap, Pattern.Next);
        documentCreating.templateSynthesis(TREE_NODES, beginTime + "", msgMap);
        for (int i = 0; i < TREE_NODES.size(); i++) {
        	log.info("进入循环--------");
            Map thisMsg = (Map) msgMap.get(i);
            Element TREE_NODE = TREE_NODES.get(i);
            thisMsg.put("FILE_NO", TREE_NODE.elementText("FILE_NO"));
            if ("1".equals(thisMsg.get("RET_CODE") + "")) {

                String filePath = (String) thisMsg.get("FILE_MSG");
                //对filepath进行处理，非pdf文件转换为pdf文件
                int len = filePath.lastIndexOf(".");
                String fileSuffix = filePath.substring(len);
                String newFilePath = filePath.substring(0, len)+".pdf";

            	if (fileSuffix.equals(".doc") || fileSuffix.equals(".docx") || fileSuffix.equals(".xls")|| fileSuffix.equals(".xlsx") || fileSuffix.equals(".ppt") || fileSuffix.equals(".pptx")) {
            		System.out.println("openObj打开之前----------");
            		log.info("openObj打开之前----------");
            		int nObjID = srvSealUtil.openObj(filePath, 0, 0);
            		System.out.println("openObj打开之后----------");
            		log.info("openObj打开之后----------");
            		log.info("nObjID:"+nObjID);
            		log.info("filePath:"+filePath);
					if(nObjID<=0){
						try {
							throw new Exception("文件转换异常");
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
					int l = srvSealUtil.login(nObjID, 4, "HWSEALDEMOXX","DEMO");
					System.out.println("login:" + l);
					if(l!=0){
						try {
							throw new Exception("文件转换异常");
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
					int save = srvSealUtil.saveFile(nObjID, newFilePath, "pdf",0);
					System.out.println("save(1为成功)：" + save);
					if(save!=1){
						try {
							throw new Exception("文件转换异常");
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				}

                String fileNo = (String) thisMsg.get("FILE_NO");
                String savePath = this.filePath + "/" + fileNo ;
                int nObjID = documentCreating.openObj(newFilePath, 0, 0);
                int ret = 1;
                log.info(beginTime + ":nObjID:" + nObjID);
                if (nObjID <= 0) {
                    log.info(beginTime + ":服务器繁忙，请稍后重试3");
                    thisMsg.put("FILE_MSG", "服务器繁忙，请稍后重试3");
                    ret = 0;
                }
                int l = documentCreating.login(nObjID, 2, "HWSEALDEMOXX", "");
                log.info(fileNo + ":login:" + l);
                try {
                    String insertCodeBarret = documentCreating.insertCodeBar(nObjID, TREE_NODE, fileNo);
                    if (!"ok".equals(insertCodeBarret)) {
                        thisMsg.put("RET_CODE", "0");
                        thisMsg.put("FILE_MSG", "添加二维码失败");
                        ret = 0;
                    }

                    int saveFileRet = documentCreating.saveFile(nObjID, savePath, syntheticType, 1);
                    if (saveFileRet == 0) {
                        log.info(beginTime + ":saveFile文档保存失败，请检查服务器，保存路径：" + savePath);
                        throw new Exception("saveFile文档保存失败");
                    } else {
                        log.info(beginTime + ":saveFile文档保存成功" + new Date() + "保存路径：" + savePath);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                } finally {
                    log.info(fileNo + ":" + i + "文档关闭");
                    documentCreating.saveFile(nObjID, "", syntheticType, 0);
                }
                if (ret == 1) {
                    String addSealret = documentCreating.addSeal(savePath, syntheticPattern, TREE_NODE, fileNo);
                    if (!"ok".equals(addSealret)) {
                        thisMsg.put("RET_CODE", "0");
                        thisMsg.put("FILE_MSG", addSealret);
                        ret = 0;
                    } else {

                        String creator = documentInfo.get("sysId");
                        String creatorName = documentInfo.get("sysId");
                        byte sourceType = Byte.parseByte(documentInfo.get("sourceType"));
                        String filepath = sealFilePath +"/"+fileNo;
                        //文件信息保存
                        boolean saveInfo = this.saveServerDocument(fileNo,creator,creatorName,filepath,sourceType);
                        if (!saveInfo){
                            log.info("向document表汇中插入文档信息失败");
                            throw new Exception("向document表汇中插入文档信息失败");
                        }

                        if (TREE_NODE.elementText("FTP_SAVEPATH") != null && !"".equals(TREE_NODE.elementText("FTP_SAVEPATH"))) {
                            String ftpUpFileRet = SignatureFileUploadAndDownLoad.ftpUpFile(TREE_NODE, sealFilePath + "/" + TREE_NODE.elementText("FILE_NO"), beginTime);
                            if ("ok".equals(ftpUpFileRet)) {
                                thisMsg.put("RET_CODE", "1");
                                thisMsg.put("FILE_MSG", "文档上传成功");
                            } else {
                                thisMsg.put("RET_CODE", "0");
                                thisMsg.put("FILE_MSG", ftpUpFileRet);
                            }

                        } else {
                            thisMsg.put("RET_CODE", "1");
                            thisMsg.put("FILE_MSG", "文档合成成功");
                        }
                    }
                }


            }

        }
        return msgMap;
    }*/

    /**
     * 文档验证接口
     *
     * @param xmlStr    请求报文
     * @param beginTime 开始时间
     * @param request
     * @return 响应报文
     */
    public String pdfVarify(String xmlStr, String beginTime, HttpServletRequest request) {
        try {
            Document doc = DocumentHelper.parseText(xmlStr);
            Element verifyDocRequest = doc.getRootElement();
            PdfVarifyCheck check = new PdfVarifyCheck(beginTime);
            boolean result = check.pdfVarify(verifyDocRequest, request);
            String FILE_PATH = null;
            String FILE_NO = null;
            String FILE_TYPE=null;
            String FTP_ADDRESS=null;
            String FTP_PORT=null;
            String FTP_USER=null;
            String FTP_PWD=null;
            if (!result) {
                log.info("xml格式判断:" + check.getError());
                try {
                    return getPdfVarifyReturnXml(verifyDocRequest.element("META_DATA").elementText("FILE_NO"), check.getError(), "0", beginTime);
                } catch (Exception e) {
                    return getPdfVarifyReturnXml(null, check.getError(), "0", beginTime);
                }
            } else {
                Element META_DATA = verifyDocRequest.element("META_DATA");
                FILE_PATH = META_DATA.elementText("FILE_PATH");
                FILE_NO = META_DATA.elementText("FILE_NO");
                FILE_TYPE=META_DATA.elementText("FILE_TYPE");
                if(FILE_TYPE!=null&&FILE_TYPE.equals("1")){
                    FTP_ADDRESS=META_DATA.elementText("FTP_ADDRESS");
                    FTP_PORT=META_DATA.elementText("FTP_PORT");
                    FTP_USER=META_DATA.elementText("FTP_USER");
                    FTP_PWD=META_DATA.elementText("FTP_PWD");
                }
                log.info("xml格式判断:成功");
            }
            Map filePaths = new HashMap();
            String FileDownRet=null;
            String ftpEncoding=null;
            if(FILE_TYPE!=null&&FILE_TYPE.equals("1")){
                FileDownRet = SignatureFileUploadAndDownLoad.ftpDownFile1(ftpEncoding, FTP_ADDRESS, FTP_PORT, FTP_USER, FTP_PWD, FILE_PATH, beginTime, filePaths);
            }else{
                FileDownRet = SignatureFileUploadAndDownLoad.httpDownFile(FILE_PATH, beginTime, filePaths);
            }
            if (!"ok".equals(FileDownRet)) {
                return getPdfVarifyReturnXml(FILE_NO, FileDownRet, "0", beginTime);
            }
            init();//初始化控件
            DocumentCreating documentCreating = (DocumentCreating) Util.getBean("documentCreating");
            int nObjID = documentCreating.openObj((String) filePaths.get("fileUrl"), 0, 0);
            log.info(beginTime + ":nObjID:" + nObjID);
            try {
                if (nObjID <= 0) {
                    log.info(beginTime + ":服务器繁忙，请稍后重试");
                    return getPdfVarifyReturnXml(FILE_NO, "服务器繁忙，请稍后重试", "0", beginTime);
                }
                if (System.getProperty("os.name").toUpperCase().indexOf("WINDOWS") != -1) {

                    String v = srvSealUtil.verify(nObjID);
                   /* Map m = varifyDateToMap(v);
                    if (Integer.parseInt(m.get("RetCode")+"")  >= 0) {
                        return getPdfVarifyReturnXml(FILE_NO, "文档验证成功,文档未被篡改", "1", beginTime);
                        //return getPdfVarifyReturnXml(FILE_NO, "文档验证成功，印章：" + m.get("NodeName") + ";证书：" + m.get("CertSubject") + ";序列号：" + m.get("CertSerial") + "证书颁发者：" + m.get("CertIssuer"), "1", beginTime);
                    } else {
                        return getPdfVarifyReturnXml(FILE_NO, "文档验证失败，文档被篡改", "0", beginTime);
                    }*/

                    //List l=varifyDateToMap(v);

                    boolean  b=varifyDate(v);
                    if(b==true){
                        return getPdfVarifyReturnXml(FILE_NO, "文档验证成功,文档未被篡改", "1", beginTime);
                    }else{
                        return getPdfVarifyReturnXml(FILE_NO, "文档验证失败，文档被篡改", "0", beginTime);
                    }

                }else{
                    String sealval=srvSealUtil.getNextSeal(nObjID,"");
                    System.out.println("sealval:"+sealval);
                    String verifyValue="";
                    if(sealval.equals("")||sealval==null){
                        return getPdfVarifyReturnXml(FILE_NO, "未发现签名数据，请检查待验证文档是否为加盖了印章的PDF文档!", "0", beginTime);
                    }
                    String sealtype=srvSealUtil.getSealInfo(nObjID, sealval, 0);
                    System.out.println("sealtype" + sealtype);
                    if(sealtype.equals("1")){
                        return "验证不通过:此盖章pdf中有非印章的元素";
                    }
                    String verifyValue1="";
                    while(!sealval.equals("")){
                       /* byte[] sealP7=srvSealUtil.getSealP7(nObjID,sealval);
                        byte[] data = srvSealUtil.getSealSignSHAData(nObjID,sealval);
                        verifyValue1=SignUtil.verifyP71(Base64.encodeBase64String(sealP7),data);
                        if (verifyValue1.equals("false")) {
                            return getPdfVarifyReturnXml(FILE_NO, "文档验证失败，文档被篡改", "0", beginTime);
                        }*/
                        /*byte[] bytecert=srvSealUtil.getSealAIPCert(nObjID, sealval);
                        System.out.println("bytecert" + bytecert);
                        byte[] byteoridata=srvSealUtil.getSealAIPOriData(nObjID, sealval);
                        System.out.println("byteoridata" + byteoridata);
                        byte[] byteaipsign=srvSealUtil.getSealAIPSign(nObjID, sealval);
                        System.out.println("byteaipsign" + byteaipsign);
                        */
                        String verifySeal=srvSealUtil.verifySeal(nObjID, sealval);
                        boolean  b=varifyDate(verifySeal);
                        log.info("linux验证文档的varifyDate-----------："+verifySeal);
                        log.info("linux验证文档的b------------："+b);
                        if(b==false){
                            return getPdfVarifyReturnXml(FILE_NO, "文档验证失败，文档被篡改", "0", beginTime);
                        }
                        return getPdfVarifyReturnXml(FILE_NO, "文档验证成功,文档未被篡改", "1", beginTime);
                    }

                    return getPdfVarifyReturnXml(FILE_NO, "文档验证成功,文档未被篡改", "1", beginTime);

                }
            } catch (Exception e) {
                return getPdfVarifyReturnXml(FILE_NO, e.getMessage(), "1", beginTime);
            } finally {
                documentCreating.saveFile(nObjID, "", syntheticType, 0);
            }


        } catch (DocumentException e) {
            return getPdfVarifyReturnXml(null, e.getMessage(), "0", beginTime);
        } catch (Exception e) {
            e.printStackTrace();
            return getPdfVarifyReturnXml(null, e.getMessage(), "0", beginTime);
        }
    }

    /**
     * 得到返回报文（合成接口用）
     *
     * @param map
     * @param folder    合成后文档目录
     * @param beginTime
     * @param checkMsg  验证错误标记
     * @return 响应报文
     */
    private String getReturnXml(Map map, String folder, long beginTime,SyntheticPattern syntheticPattern, String... checkMsg) {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        //String retXml = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>" + (syntheticPattern==SyntheticPattern.AddSeal?"<SEAL_DOC_RESPONSE>":"<MODEL_REQUEST>");
        String retXml = "<?xml version=\"1.0\" encoding=\"utf-8\" ?>" + (syntheticPattern==SyntheticPattern.AddSeal?"<SEAL_DOC_RESPONSE>":(syntheticPattern==null?"<WORD_TO_PDF_RESPONSE>":"<MODEL_RESPONSE>"));
        String msg = "";

        if (checkMsg.length == 0) {
            msg = "<RET_CODE>" + 1 + "</RET_CODE>"
                    + "<RET_MSG>xml验证成功</RET_MSG>";
            String ret_type="";
            if(map.containsKey("RET_FILE_TYPE")){
                ret_type=map.get("RET_FILE_TYPE").toString();
                map.remove("RET_FILE_TYPE");
            }

            String serverip=Util.getSystemDictionary("serverIp");
            if(serverip==null){
                serverip=request.getLocalAddr();
            }
            String serverport=Util.getSystemDictionary("serverPort");
            if(serverport==null){
                serverport=request.getLocalPort()+"";
            }
            log.info("serverip:"+serverip);
            log.info("serverport:"+serverport);

            Iterator iterator = map.values().iterator();
            msg += "<FILE_LIST>";

            while (iterator.hasNext()) {
                Map m = (Map) iterator.next();
                String fileData="";
                if("BASE64".equalsIgnoreCase(ret_type)) {
                    fileData="<FILE_DATA>"+Util.getFileBase64(Util.getSystemDictionary("upload_path")+"/" + folder + "/" + m.get("FILE_NO"))+"</FILE_DATA>";
                }else{
                    fileData="<FILE_URL>" + (Integer.parseInt(m.get("RET_CODE") + "") == 0 ? "" : ("http://" + serverip + ":" + serverport + "" + Util.getSystemDictionary("server.contextPath") + "/file/" + folder + "?name=" + m.get("FILE_NO"))) + "</FILE_URL>";
                }
                msg += "<FILE><RET_CODE>" + m.get("RET_CODE") + "</RET_CODE>"
                        + "<FILE_NO>" + m.get("FILE_NO") + "</FILE_NO>"
                        + "<FILE_MSG>" + m.get("FILE_MSG") + "</FILE_MSG>"
                        + fileData+"</FILE>";
            }
            msg += "</FILE_LIST>";
        } else {
            msg += "<RET_CODE>" + 0 + "</RET_CODE>"
                    + "<RET_MSG>" + checkMsg[0] + "</RET_MSG>"
                    + "<FILE_LIST></FILE_LIST>";
        }
        retXml += "<SEAL_TIME>" + (new Date().getTime() - beginTime) + "</SEAL_TIME>"
                + msg
                //+ (syntheticPattern==SyntheticPattern.AddSeal?"</SEAL_DOC_RESPONSE>":"</MODEL_REQUEST>");
                + (syntheticPattern==SyntheticPattern.AddSeal?"</SEAL_DOC_RESPONSE>":(syntheticPattern==null?"</WORD_TO_PDF_RESPONSE>":"</MODEL_RESPONSE>"));
        return retXml;
    }

    private String getPdfVarifyReturnXml(String fileNo, String retMsg, String regCode, String beginTime) {
        StringBuffer sb = new StringBuffer("<?xml version=\"1.0\" encoding=\"utf-8\" ?>");
        sb.append("<VERIFY_DOC_RESPONSE>");
        sb.append("<RET_CODE>").append(regCode).append("</RET_CODE>");//1 代表校验通过
        if (fileNo != null) {
            sb.append("<FILE_NO>").append(fileNo).append("</FILE_NO>");
        }
        sb.append("<RET_MSG>").append(retMsg).append("</RET_MSG>");
        sb.append("</VERIFY_DOC_RESPONSE>");
        return sb.toString();
    }

    private List<Map> varifyDateToMap(String varifyDate) {
        if (varifyDate != null && !varifyDate.equals("")) {
            List returnList=new ArrayList();

            String[] s= varifyDate.split("<+");
            for(int j=0;j<s.length;j++){
                Map retMap = new HashMap();
                varifyDate = varifyDate.substring(varifyDate.indexOf("<+")+2, varifyDate.indexOf("/;->"));
                String[] nodes = varifyDate.split("/;");
                for (int i = 0; i < nodes.length; i++) {
                    String[] node = nodes[i].split("=");
                    retMap.put(node[0], node[1]);
                }
                returnList.add(retMap);
            }
            return returnList;
        } else {
            return null;
        }

    }


    private boolean varifyDate(String varifyDate) {
        if (varifyDate != null && !varifyDate.equals("")) {
            String[] s= varifyDate.split("<+");
            for(int j=1;j<s.length;j++){
                // Map retMap = new HashMap();
                String s1 = s[j].substring(s[j].indexOf("+")+1, s[j].indexOf("/;->"));
                String[] nodes = s1.split("/;");
                for (int i = 0; i < nodes.length; i++) {
                    String[] node = nodes[i].split("=");
                    //retMap.put(node[0], node[1]);
                    if(node[0].equals("RetCode")){
                        if(Integer.parseInt(node[1])<0)
                            return false;
                    }
                }

            }
            return true;
        } else {
            return false;
        }

    }

    private void varifySystemType() throws DJException {
        if(((LicenseConfig.systemType&LicenseConfig.SystemType_Server)==LicenseConfig.SystemType_Server)||
                ((LicenseConfig.systemType1&LicenseConfig.SystemType1_GJZWYY)==LicenseConfig.SystemType1_GJZWYY)
                ){
        }else{
            String str="拒绝服务：未授权的服务器类型";
            log.info(str);
            throw new DJException(str);
        }
    }

    /**
     * 文件转图片
     * @param xmlStr
     * @param fileId
     * @param request
     * @return
     */
    public String fileToPicture(String xmlStr,String fileId,HttpServletRequest request){
        init();//
        Document doc;
        long beginTime=System.currentTimeMillis();
        try {
            varifySystemType();
            doc = DocumentHelper.parseText(xmlStr);
            Element fileToPictureRequest = doc.getRootElement();
            FileToPictureCheck fileToPictureCheck=	new FileToPictureCheck(fileId+"");
            if (! fileToPictureCheck.fileToPictureCheck(fileToPictureRequest, request)){
                log.info(fileId+":"+fileToPictureCheck.getError());
            }
            //获取扩展信息META_DATA
            Element	metaData = fileToPictureRequest.element("META_DATA");
            String	FILE_NO=metaData.element("FILE_NO").getTextTrim();
            String	FILE_PATH=metaData.element("FILE_PATH").getTextTrim();
            String  PICTURE_TYPE=metaData.element("PICTURE_TYPE").getTextTrim();
            String  PICTURE_WIDTH=metaData.element("PICTURE_WIDTH").getTextTrim();
            String  MODE=metaData.element("MODE").getTextTrim();
            Map<String, String> fileMsg=new HashMap<String, String>();
            String savePath=path+"/download/"; //下载文件存放路径
            Util.createDirs(savePath);
            if(SignatureFileUploadAndDownLoad.httpDownFile(FILE_PATH, beginTime+"", fileMsg).equals("ok")){
                String filePath=fileMsg.get("fileUrl");
                String	dir= UUIDReduce.uuid();
                String imgFolder=fileToPicture+"img/"+dir+"/";
                Util.createFile(imgFolder);
                Map<String, Integer> m=new HashMap<String, Integer>();
                String pdfToImgAllPageRet=djPdfToImgUtil.pdfToImgAllPage(filePath, imgFolder, Integer.parseInt(PICTURE_WIDTH), PICTURE_TYPE,m);
                if("success".equals(pdfToImgAllPageRet)){
                    int pagenum=m.get("pagenum");
                    if(MODE.toLowerCase().equals("zip")){
                        String zipPath=fileToPicture+"/zip/";
                        Util.createFile(imgFolder);
                        zipPath=zipPath+FILE_NO+".zip";
                        Util.deleteFile(zipPath);
                        if(ZipUtil.zip(imgFolder, zipPath,false, null)!=null){
                            return	getFileToPictureRetXml(1,"转换成功","http://" + request.getLocalAddr() + ":" + request.getLocalPort() +Util.getSystemDictionary("server.contextPath")+"/file/fileToPicture/zip?name="+FILE_NO+".zip",System.currentTimeMillis()-beginTime,pagenum);
                        }else{
                            return	getFileToPictureRetXml(0,"zip压缩失败",null,System.currentTimeMillis()-beginTime,pagenum);
                        }
                    }else if(MODE.toLowerCase().equals("show")){
                        String ret="<body  style='background-color:_CCCCCC;text-align:center'>";
                        for(int i=1;i<=pagenum;i++){
                            String fileUrl=	Util.getSystemDictionary("server.contextPath")+"/file?name=fileToPicture_img_"+dir+"_"+i+"."+PICTURE_TYPE;
                            ret+="<img  style='margin-left:auto;margin-right:auto' width='"+PICTURE_WIDTH+"px' src='"+fileUrl+"'><br/><br/>";
                        }
                        ret=ret+"</body>";
                        return ret;
                    }else{
                        return	getFileToPictureRetXml(1,"转换成功","http://" + request.getLocalAddr() + ":" + request.getLocalPort() +Util.getSystemDictionary("server.contextPath")+"/file/fileToPicture/img/"+dir,System.currentTimeMillis()-beginTime,pagenum);
                    }
                }else{
                    return	getFileToPictureRetXml(0,"转换失败:"+pdfToImgAllPageRet,null,System.currentTimeMillis()-beginTime,0);
                }


            }else{
                return	getFileToPictureRetXml(0,"文档下载失败",null,System.currentTimeMillis()-beginTime,0);
            }

        } catch (DocumentException e) {
            return	getFileToPictureRetXml(0,e.getMessage(),null,System.currentTimeMillis()-beginTime,0);
        } catch (DJException e) {
            return	getFileToPictureRetXml(0,e.getMessage(),null,System.currentTimeMillis()-beginTime,0);
        }
    }


    public static boolean isNumeric(String str){
        for(int i=str.length();--i>=0;){
            int chr=str.charAt(i);
            if(chr<48 || chr>57)
                return false;
        }
        return true;
    }

    /**
     * 文件转图片
     * @param FILE_NO
     * @param FILE_PATH
     * @param PICTURE_WIDTH
     * @param PICTURE_TYPE
     * @param fileId
     * @param request
     * @return
     */
    public int fileToPictureTest(String FILE_NO,String FILE_PATH,String PICTURE_WIDTH,String PICTURE_TYPE,String fileId,HttpServletRequest request){
        init();
        //Document doc;
        long beginTime=System.currentTimeMillis();
        try {
            varifySystemType();
            Map<String, String> fileMsg=new HashMap<String, String>();
            String savePath=path+"/download/"; //下载文件存放路径
            Util.createDirs(savePath);
            if(SignatureFileUploadAndDownLoad.httpDownFile(FILE_PATH, beginTime+"", fileMsg).equals("ok")){
                String filePath=fileMsg.get("fileUrl");
                String	dir= UUIDReduce.uuid();
                //文件存放路径
                String imgFolder=fileToPicture;
                Util.createFile(imgFolder);
                Map<String, Integer> m=new HashMap<String, Integer>();
                String pdfToImgAllPageRet=djPdfToImgUtil.pdfToImgAllPage1(filePath, imgFolder, FILE_NO ,Integer.parseInt(PICTURE_WIDTH), PICTURE_TYPE,m);
                if(isNumeric(pdfToImgAllPageRet)==true){
                    int pagecount = Integer.valueOf(pdfToImgAllPageRet);
                    return pagecount;

                }else{
                    return 0;
                }


            }else{
                return 0;
            }
        } catch (DJException e) {
            return 0;
        }
    }
    /*public int fileToPictureTest(String xmlStr,String fileId,HttpServletRequest request){
        init();
        Document doc;
        long beginTime=System.currentTimeMillis();
        try {
            varifySystemType();
            doc = DocumentHelper.parseText(xmlStr);
            Element fileToPictureRequest = doc.getRootElement();
            FileToPictureCheck fileToPictureCheck=	new FileToPictureCheck(fileId+"");
            if (! fileToPictureCheck.fileToPictureCheck(fileToPictureRequest, request)){
                log.info(fileId+":"+fileToPictureCheck.getError());
            }
            //获取扩展信息META_DATA
            Element	metaData = fileToPictureRequest.element("META_DATA");
            String	FILE_NO=metaData.element("FILE_NO").getTextTrim();
            String	FILE_PATH=metaData.element("FILE_PATH").getTextTrim();
            String  PICTURE_TYPE=metaData.element("PICTURE_TYPE").getTextTrim();
            String  PICTURE_WIDTH=metaData.element("PICTURE_WIDTH").getTextTrim();
            String  MODE=metaData.element("MODE").getTextTrim();
            Map<String, String> fileMsg=new HashMap<String, String>();
            String savePath=path+"/download/"; //下载文件存放路径
            Util.createDirs(savePath);
            if(SignatureFileUploadAndDownLoad.httpDownFile(FILE_PATH, beginTime+"", fileMsg).equals("ok")){
                String filePath=fileMsg.get("fileUrl");
                String	dir= UUIDReduce.uuid();
                //文件存放路径
                String imgFolder=fileToPicture+"img/";
                Util.createFile(imgFolder);
                Map<String, Integer> m=new HashMap<String, Integer>();
                String pdfToImgAllPageRet=djPdfToImgUtil.pdfToImgAllPage1(filePath, imgFolder, FILE_NO ,Integer.parseInt(PICTURE_WIDTH), PICTURE_TYPE,m);
                if(isNumeric(pdfToImgAllPageRet)==true){
                	int pagecount = Integer.valueOf(pdfToImgAllPageRet);
                	return pagecount;
                    int pagenum=m.get("pagenum");
                    if(MODE.toLowerCase().equals("zip")){
                        String zipPath=fileToPicture+"/zip/";
                        Util.createFile(imgFolder);
                        zipPath=zipPath+FILE_NO+".zip";
                        Util.deleteFile(zipPath);
                        if(ZipUtil.zip(imgFolder, zipPath,false, null)!=null){
                            return	getFileToPictureRetXml(1,"转换成功","http://" + request.getLocalAddr() + ":" + request.getLocalPort() +Util.getSystemDictionary("server.contextPath")+"/file/fileToPicture/img?name="+pdfToImgAllPageRet,System.currentTimeMillis()-beginTime,pagenum);
                        }else{
                            return	getFileToPictureRetXml(0,"zip压缩失败",null,System.currentTimeMillis()-beginTime,pagenum);
                        }
                    }else if(MODE.toLowerCase().equals("show")){
                        String ret="<body  style='background-color:_CCCCCC;text-align:center'>";
                        for(int i=1;i<=pagenum;i++){
                            String fileUrl=	Util.getSystemDictionary("server.contextPath")+"/file?name=fileToPicture_img_"+dir+"_"+i+"."+PICTURE_TYPE;
                            ret+="<img  style='margin-left:auto;margin-right:auto' width='"+PICTURE_WIDTH+"px' src='"+fileUrl+"'><br/><br/>";
                        }
                        ret=ret+"</body>";
                        return ret;
                    }else{
                        return	getFileToPictureRetXml(1,"转换成功","http://" + request.getLocalAddr() + ":" + request.getLocalPort() +Util.getSystemDictionary("server.contextPath")+"/file/fileToPicture/img/"+dir,System.currentTimeMillis()-beginTime,pagenum);
                    }
                }else{
                	return 0;
                    //return	getFileToPictureRetXml(0,"转换失败:"+pdfToImgAllPageRet,null,System.currentTimeMillis()-beginTime,0);
                }


            }else{
                return 0;
            }

        } catch (DocumentException e) {
        	return 0;
            //return	getFileToPictureRetXml(0,e.getMessage(),null,System.currentTimeMillis()-beginTime,0);
        } catch (DJException e) {
        	return 0;
            //return	getFileToPictureRetXml(0,e.getMessage(),null,System.currentTimeMillis()-beginTime,0);
        }
    }*/
    public String ofdToPicture(String ofdFilePath, String fileId, HttpServletRequest request) {
        long beginTime = System.currentTimeMillis();

        Map<String, String> fileMsg = new HashMap<String, String>();

        String savePath = Util.getSystemDictionary("upload_path") + "/pdfToImg/download/" + fileId+"/";
        Util.createDires(savePath);
        if (SignatureFileUploadAndDownLoad.httpDownFile(ofdFilePath, savePath, fileId + "", fileMsg).equals("ok")) {
            String filePath = fileMsg.get("fileUrl");
            String dir = UUID.randomUUID().toString();
            String imgFolder = Util.getSystemDictionary("upload_path") + "/pdfToImg/img/" + dir + "/";
            Util.createFile(imgFolder);
            Map<String, Integer> m = new HashMap<String, Integer>();
            String pdfToImgAllPageRet = djPdfToImgUtil.pdfToImgAllPage(filePath, imgFolder, Integer.parseInt("750"), "jpg", m);
            if ("success".equals(pdfToImgAllPageRet)) {
                int pagenum = m.get("pagenum");
                String ret = "<!DOCTYPE html>\n" +
                        "<html>\n" +
                        "<head>\n" +
                        "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\"  />\n" +
                        "<meta http-equiv=\"Pragma\" content=\"no-cache\"/>\n" +
                        "<meta http-equiv=\"Expires\" content=\"0\"/>\n" +
                        "<meta http-equiv=\"Cache-Control\" content=\"no-cache\"/>\n" +
                        "<meta http-equiv=\"X-UA-Compatible\" />\n" +
                        "</head><body  style='background-color:#CCCCCC;text-align:center'>";
                for (int i = 1; i <= pagenum; i++) {
                    String fileUrl = "file/pdfToImg/img/" + dir + "?name=" + i + "." + "jpg";
                    ret += "<img  style='margin-left:auto;margin-right:auto' width='" + 750 + "px' src='" + fileUrl + "'><br/><br/>";
                }
                ret = ret + "</body></html>";
                return ret;
            } else {
                return getFileToPictureRetXml(0, "转换失败:" + pdfToImgAllPageRet, null, System.currentTimeMillis() - beginTime, 0);
            }
        } else {
            return getFileToPictureRetXml(0, "文档下载失败", null, System.currentTimeMillis() - beginTime, 0);
        }
    }
    private  String getFileToPictureRetXml(int ret,String msg,String path,long switchTime,int pageCount){
        String retStr="<?xml version=\"1.0\" encoding=\"utf-8\" ?><FILE_TO_PICTURE_RESPONSE><SWITCH_TIME>"+switchTime+"</SWITCH_TIME><RET_CODE>"+ret+"</RET_CODE><RET_MSG>"+msg+"</RET_MSG><PAGE_COUNT>"+pageCount+"</PAGE_COUNT><FILE_URL>"+path+"</FILE_URL></FILE_TO_PICTURE_RESPONSE>";
        return retStr;
    }
    public String fileToOnePicture(String xmlStr, String fileId, HttpServletRequest request){
        log.info("报文内容为：："+xmlStr);
        Document doc;
        long beginTime = System.currentTimeMillis();
        try {
            doc = DocumentHelper.parseText(xmlStr);
            Element fileToPictureRequest = doc.getRootElement();
            FileToPictureCheck fileToPictureCheck = new FileToPictureCheck(fileId + "");
            if (!fileToPictureCheck.fileToPictureCheck(fileToPictureRequest, request)) {
            } else {
                log.info(fileId + ":" + fileToPictureCheck.getError());
            }
            //获取扩展信息META_DATA
            Element metaData = fileToPictureRequest.element("META_DATA");
            String FILE_NO = metaData.element("FILE_NO").getTextTrim();
            String FILE_PATH = metaData.element("FILE_PATH").getTextTrim();
            String PICTURE_TYPE = metaData.element("PICTURE_TYPE").getTextTrim();
            String PICTURE_WIDTH = metaData.element("PICTURE_WIDTH").getTextTrim();
            String MODE = metaData.element("MODE").getTextTrim();
            Map<String, String> fileMsg = new HashMap<String, String>();

            String savePath = Util.getSystemDictionary("upload_path") + "/pdfToImg/download/" + FILE_NO;
            Util.createDires(savePath);
            if (SignatureFileUploadAndDownLoad.httpDownFile(FILE_PATH, savePath, fileId + "", fileMsg).equals("ok")) {
                String filePath = fileMsg.get("fileUrl");
                String dir = UUID.randomUUID().toString();
                String imgFolder = Util.getSystemDictionary("upload_path") + "/pdfToImg/img/" + dir + "/";
                Util.createFile(imgFolder);
                Map<String, Integer> m = new HashMap<String, Integer>();
                String pdfToImgAllPageRet = djPdfToImgUtil.pdfToOneImgAllPage(filePath, imgFolder, FILE_NO, PICTURE_TYPE, m);
                if ("success".equals(pdfToImgAllPageRet)) {
                    return getFileToPictureRetXml(1, "转换成功", "http://" + request.getLocalAddr() + ":" + request.getLocalPort() + Util.getSystemDictionary("server.contextPath") + "/file/pdfToImg/img/"+ dir +"?name=" + FILE_NO , System.currentTimeMillis() - beginTime, 0);
                } else {
                    return getFileToPictureRetXml(0, "转换失败:" + pdfToImgAllPageRet, null, System.currentTimeMillis() - beginTime, 0);
                }


            } else {
                return getFileToPictureRetXml(0, "文档下载失败", null, System.currentTimeMillis() - beginTime, 0);
            }

        } catch (DocumentException e) {
            return getFileToPictureRetXml(0, e.getMessage(), null, System.currentTimeMillis() - beginTime, 0);
        }
    }


    /**
     * pdf添加水印
     * @param xmlStr   请求报文
     * @param beginTime  开始时间
     * @param request
     * @return
     */
    public String addWatermarkToPdf(String xmlStr, String beginTime, HttpServletRequest request) {
        /*用于封装返回报文信息*/
        String returnXml = "";
        Map retMap = new HashMap();
        DocumentCreating documentCreating = (DocumentCreating) Util.getBean("documentCreating");
        try {
            /*获取报文中关于水印的信息*/
            Document doc = DocumentHelper.parseText(xmlStr);
            Element sealDocRequest = doc.getRootElement();
            Element TREE_NODE = sealDocRequest.element("FILE_LIST").element("TREE_NODE");
            String IS_WATERMARK = TREE_NODE.elementText("IS_WATERMARK");
            String FILE_NO = TREE_NODE.elementText("FILE_NO");//文件名
            String REQUEST_TYPE = TREE_NODE.elementText("REQUEST_TYPE");//读取文件的方式ftp或者http

            /* pdf文件下载到本地*/
            Map filePaths = new HashMap();
            String FileDownRet=null;
            //String ftpEncoding=null;
            if("1".equals(REQUEST_TYPE)){//ftp
            }else{//http
                String FILE_PATH = TREE_NODE.elementText("FILE_PATH");//文件下载路径
                FileDownRet = SignatureFileUploadAndDownLoad.httpDownFile(FILE_PATH, beginTime, filePaths);
            }
            /*下载失败返回文件名*/
            if (!"ok".equals(FileDownRet)) {
                return getPdfVarifyReturnXml(FILE_NO, FileDownRet, "0", beginTime);
            }

            /*根据下载后的文件地址，生成pdf文档id*/
            int nObjID = documentCreating.openObj((String) filePaths.get("fileUrl"), 0, 0);

            if ("1".equals(IS_WATERMARK)) {
                /*取水印信息*/
                String WATERMARK_MODE = TREE_NODE.elementText("WATERMARK_MODE");//水印模式 short 设置或返回水印模式： 1：居中 (文字)2：平铺 (文字)3：居中带阴影(文字)4：平铺带阴影(文字)7：指定像素值
                String WATERMARK_ALPHA = TREE_NODE.elementText("WATERMARK_ALPHA");//水印透明度值范围：1到63，愈大愈透明。
                String WATERMARK_TYPE = TREE_NODE.elementText("WATERMARK_TYPE");//水印类型1是文字水印2是图片水印
                String WATERMARK_TEXTORPATH = TREE_NODE.elementText("WATERMARK_TEXTORPATH");//文字水印信息或图片base64数据
                String WATERMARK_POSX = TREE_NODE.elementText("WATERMARK_POSX");//水印在文档的x坐标位置
                String WATERMARK_POSY = TREE_NODE.elementText("WATERMARK_POSY");//水印在文档的y坐标位置
                String WATERMARK_TEXTCOLOR = TREE_NODE.elementText("WATERMARK_TEXTCOLOR");//水印文字颜色
                String WATERMARK_ANGLE = TREE_NODE.elementText("WATERMARK_ANGLE");//旋转角度
                String WATERMARK_TXTHORIMGZOOM = TREE_NODE.elementText("WATERMARK_TXTHORIMGZOOM");//缩放比例*/

                /*添加水印信息*/
                init();//初始化控件
                int l = documentCreating.login(nObjID, 2, "HWSEALDEMOXX", "");
                log.info(beginTime + ":login:" + l);
                if (l != 0) {
                    throw new Exception("未授权的服务器");
                }
                srvSealUtil.setValue(nObjID, "SET_WATERMARK_MODE", WATERMARK_MODE);
                if ("1".equals(WATERMARK_TYPE)) {//文字水印
                    srvSealUtil.setValue(nObjID, "SET_WATERMARK_TEXTORPATH", "STRDATA:"+WATERMARK_TEXTORPATH);

                } else if ("2".equals(WATERMARK_TYPE)) {//图片水印
                    srvSealUtil.setValue(nObjID, "SET_WATERMARK_TEXTORPATH", WATERMARK_TEXTORPATH);
                    srvSealUtil.setValue(nObjID, "SET_WATERMARK_TEXTCOLOR", WATERMARK_TEXTCOLOR);
                }
                srvSealUtil.setValue(nObjID, "SET_WATERMARK_ALPHA", WATERMARK_ALPHA);
                srvSealUtil.setValue(nObjID, "SET_WATERMARK_POSX", WATERMARK_POSX);
                srvSealUtil.setValue(nObjID, "SET_WATERMARK_POSY", WATERMARK_POSY);
                srvSealUtil.setValue(nObjID, "SET_WATERMARK_ANGLE", WATERMARK_ANGLE);
                srvSealUtil.setValue(nObjID, "SET_WATERMARK_TXTHORIMGZOOM", WATERMARK_TXTHORIMGZOOM);

                String savePath = filePath + "/" + FILE_NO.substring(0,FILE_NO.lastIndexOf("."))+ "." + syntheticType;
                int saveFileRet = documentCreating.saveFile(nObjID, savePath, syntheticType, 0);

                log.info(beginTime + ":saveFile:" + saveFileRet);
                if (saveFileRet == 0) {
                    retMap.put("RET_CODE", "1失败");
                    retMap.put("FILE_MSG", "水印添加失败");
                    log.info(beginTime + ":saveFile文档保存失败，请检查服务器，保存路径：" + savePath);
                    throw new Exception("saveFile文档保存失败");
                } else {
                    log.info(beginTime + ":saveFile文档保存成功" + new Date() + "保存路径：" + savePath);
                    retMap.put("RET_CODE", "0成功");
                    retMap.put("FILE_MSG", "文档添加水印成功");
                    retMap.put("FILE_NO",FILE_NO);
                }
                returnXml=  getReturnXml(retMap, filePath.substring(filePath.lastIndexOf("/")+1), beginTime);
            }else{
                log.info("报文显示无需添加水印");
                throw new Exception("xml显示无需添加水印");
            }
        } catch (DocumentException e) {
            log.info(beginTime + "");
            e.printStackTrace();
            return getReturnXml(null, "", beginTime, "xml解析失败");
        } catch (Exception e) {
            e.printStackTrace();
            return getReturnXml(null, "", beginTime,  e.getMessage());
        }finally {
            log.info("saveFile文档关闭");
        }
        return returnXml;
    }


    /**
     * word  pdf——>Ofd 文档类型转换
     * @param xmlStr
     * @param beginTime
     * @param request
     * @return
     */
    public String wordPdfToOfd(String xmlStr,long beginTime, HttpServletRequest request) {
        init();//初始化控件
        //创建所有文档转换需要的文件夹(如果没有则创建)
        String upload_path = Util.getSystemDictionary("upload_path")+"/";
        String downPathHttp = Util.getSystemDictionary("downPathHttp");
        String filePath = Util.getSystemDictionary("filePath");
        Util.createDires(upload_path+downPathHttp);
        Util.createDires(upload_path+filePath);
        try{
            HttpSession session = request.getSession();
            Document doc = DocumentHelper.parseText(xmlStr);
            Element wordToPdfRequest = doc.getRootElement();
            String returnXml = null;
            SealAutoPdfCheck check = new SealAutoPdfCheck(beginTime+"");
            Element FILE_LIST = wordToPdfRequest.element("FILE_LIST");
            List<Element> TREE_NODES = FILE_LIST.elements("TREE_NODE");
            Map msgMap = new HashMap<Integer, Map<String, String>>();
            if (!check.wordToPdf(wordToPdfRequest,request)){
                //xml格式判断失败
                return getReturnXml(null, "", beginTime,null, check.getError());
            }else {
                //1.下载文件
                SignatureFileUploadAndDownLoad.downFile(TREE_NODES, beginTime + "", msgMap, Pattern.Next);
                //2.文件类型转换
                for (int i = 0; i < TREE_NODES.size(); i++) {
                    log.info("进入循环---------");
                    Map thisMsg = (Map) msgMap.get(i);
                    Element TREE_NODE = TREE_NODES.get(i);
                    thisMsg.put("FILE_NO", TREE_NODE.elementText("FILE_NO"));
                    if ("1".equals(thisMsg.get("RET_CODE") + "")) {
                        log.info("进入循环2------");
                        String filePath1 = (String) thisMsg.get("FILE_MSG");
                        //filePath = (String) thisMsg.get("FILE_MSG");
                        //对filepath进行处理，非pdf文件转换为pdf文件
                        int len = filePath1.lastIndexOf(".");
                        String fileSuffix = filePath1.substring(len);
                        //     String newFilePath = filePath1.substring(0, len) + ".pdf";
                        log.info("日志1---------");
                        String fileNo = (String) thisMsg.get("FILE_NO");
                        log.info("fileNo:" + fileNo);
                        String  pdfPath =  UUIDReduce.uuid()+".pdf";
                        String savePath = this.filePath + "/" + pdfPath;

                        String  savePath1 = fileNo;
                        String saveOfdPath = this.filePath + "/"+savePath1 ;
                        thisMsg.put("FILE_NO", savePath1);
                        if (fileSuffix.equals(".doc") || fileSuffix.equals(".docx")) {//wordtoPDF
                            if (System.getProperty("os.name").toUpperCase().indexOf("WINDOWS") != -1) {
                                //window转化
                                int otp = srvSealUtil.officeToPdf(-1, filePath1, savePath);//doc转换成pdf
                                if (otp < 1) {
                                    thisMsg.put("FILE_MSG", "officeToOfd失败,"+otp);
                                    srvSealUtil.saveFile(otp, "", "pdf",0);
                                    return getReturnXml(null, "", beginTime,null, "officeToOfd失败,"+otp);
                                }
                            }else{
                                //linux转化
                                wordToPdf(TREE_NODES.get(i), filePath1, savePath);
                            }
                            int nObjID = srvSealUtil.openObj(savePath, 0, 0);
                            if(nObjID>0){
                                int save = srvSealUtil.saveFile(nObjID, saveOfdPath, "ofd",0);//pdf保存成ofd的控件
                                System.out.println(save);
                            }else{
                                srvSealUtil.saveFile(nObjID, "", "pdf",0);
                                thisMsg.put("FILE_MSG", "officeToOfd失败,"+nObjID);
                                return getReturnXml(null, "", beginTime, null, "转换文件类型错误");
                            }

                        }else if(fileSuffix.equals(".pdf")){
                            int nObjID = srvSealUtil.openObj(filePath1, 0, 0);
                            if(nObjID>0){
                                int save = srvSealUtil.saveFile(nObjID, saveOfdPath, "ofd",0);//pdf保存成ofd的控件
                                System.out.println(save);
                            }else{
                                srvSealUtil.saveFile(nObjID, "", "pdf",0);
                                thisMsg.put("FILE_MSG", "officeToOfd失败,"+nObjID);
                                return getReturnXml(null, "", beginTime, null, "转换文件类型错误");
                            }
                        } else {//上传文档类型错误
                            return getReturnXml(null, "", beginTime, null, "转换文件类型错误");
                        }
                        thisMsg.put("FILE_MSG", "文档转换成功");
                        returnXml = getReturnXml(msgMap, "filePath", beginTime, null);
                    } else {
                        return getReturnXml(null, "", beginTime, null, "文档下载失败");

                    }

                }
            }
            return returnXml;
        }catch (DocumentException e){
            e.printStackTrace();
            return getReturnXml(null, "", beginTime,null, "xml解析失败");
        }catch (Exception e){
            e.printStackTrace();
            return getReturnXml(null, "", beginTime,null, "文档转换失败");
        }
    }

    /***
     * h5合成手写签名
     * @param fileName    源文件路径
     * @param filePath    保存文件路径
     * @param H5Data       h5数据
     * @param request
     * 将原来的文件打开
     * @return
     */
    public String  mergerH5Signature(String xmlStr,SyntheticPattern syntheticPattern,long beginTime, HttpServletRequest request){
        //创建所有服务端签章需要的文件夹(如果没有则创建)
        String upload_path = Util.getSystemDictionary("upload_path")+"/";
        String downPathFtp = Util.getSystemDictionary("downPathFtp");
        String downPathHttp = Util.getSystemDictionary("downPathHttp");
        String filePath4 = Util.getSystemDictionary("filePath");
        String sealFilePath = Util.getSystemDictionary("sealFilePath");
        String templateSynthesis = Util.getSystemDictionary("templateSynthesis");
        Util.createDires(upload_path+downPathFtp);
        Util.createDires(upload_path+downPathHttp);
        Util.createDires(upload_path+filePath4);
        Util.createDires(upload_path+sealFilePath);
        Util.createDires(upload_path+templateSynthesis);
        try {
            xmlStr = xmlStr.replace("&", "&amp;");//解决特殊字符不可以的问题
            Document doc = DocumentHelper.parseText(xmlStr);
            Element rootElement = doc.getRootElement();
            String returnXml = null;
            SealAutoPdfCheck check = new SealAutoPdfCheck(beginTime+"");
            Element FILE_LIST = rootElement.element("FILE_LIST");
            List<Element> TREE_NODES = FILE_LIST.elements("TREE_NODE");
            Map msgMap = new HashMap<Integer, Map<String, String>>();
            if (!check.wordToPdf(rootElement,request)){
                //xml格式判断失败
                return getReturnXml(null, "", beginTime,syntheticPattern, check.getError());
            }else {
                //1.下载文件
                SignatureFileUploadAndDownLoad.downFile(TREE_NODES, beginTime + "", msgMap, Pattern.Next);
                //2.文件类型转换
                for (int i = 0; i < TREE_NODES.size(); i++) {
                    log.info("进入循环---------");
                    Map thisMsg = (Map) msgMap.get(i);
                    Element TREE_NODE = TREE_NODES.get(i);
                    thisMsg.put("FILE_NO", TREE_NODE.elementText("FILE_NO"));
                    if ("1".equals(thisMsg.get("RET_CODE") + "")) {
                        String handwrite_position =  TREE_NODE.elementText("HANDWRITE_POSITION");
                        String filePath2 = (String) thisMsg.get("FILE_MSG");
                        if (handwrite_position == null) {
                            thisMsg.put("FILE_MSG", "HANDWRITE_POSITION不能为空");
                            return getReturnXml(null, "", beginTime,syntheticPattern, "HANDWRITE_POSITION不能为空");

                        }
                        String[] pos_strs = handwrite_position.split(",");
                        if(pos_strs.length!=5){
                            thisMsg.put("FILE_MSG", "HANDWRITE_POSITION数据格式有误");
                            return getReturnXml(null, "", beginTime,syntheticPattern, "HANDWRITE_POSITION数据格式有误");
                        }
                        //解析手写区坐标
                        int pageno;
                        int x_pos;
                        int y_pos;
                        int node_width;
                        int node_height;
                        try {
                            pageno = Integer.parseInt(pos_strs[0]);
                            x_pos = Integer.parseInt(pos_strs[1]);
                            y_pos = Integer.parseInt(pos_strs[2]);
                            node_width = Integer.parseInt(pos_strs[3]);
                            node_height = Integer.parseInt(pos_strs[4]);
                        } catch (Exception e) {
                            e.printStackTrace();
                            thisMsg.put("FILE_MSG", "HANDWRITE_POSITION数据格式有误");
                            return getReturnXml(null, "", beginTime,syntheticPattern, "HANDWRITE_POSITION数据格式有误");
                        }

                        //打开文档   所有的方法区分是Windows的还是Linux的
                        SrvSealUtil srv_seal = new SrvSealUtil();
                        //int nObjID = srv_seal.openObj(savePath, 0, 0);// SrvSealUtil.java
                        int nObjID = ocxOpenObj(srv_seal,filePath2, 0, 0);
                        System.out.println("makeFilesH5-nObjID:" + nObjID);
                        if(nObjID<1){
                            srv_seal.saveFile(nObjID, "", "pdf",0);
                            return getReturnXml(null, "", beginTime, syntheticPattern, "文件打开失败，返回值为 ："+ nObjID);
                        }
                        //int l = srv_seal.login(nObjID, 4, "HWSEALDEMOXX", "DEMO");// SrvSealUtil.java
                        int  l  = ocxLogin(srv_seal, nObjID, 2, "dj","");
                        System.out.println("makeFilesH5-login:" + l);
                        if(l<0){
                            srv_seal.saveFile(nObjID, "", "pdf",0);
                            return getReturnXml(null, "", beginTime, syntheticPattern, "登录失败，结果值为 ："+ l);
                        }
                        String handType = TREE_NODE.elementText("HAND_TYPE");//0代表h5数据，1代表图片数据
                        //单签名合成
                        String htmldata = TREE_NODE.elementText("H5_DATA");
                        if(htmldata==null||htmldata.equals("")) {
                            ocxSaveFile(srv_seal, nObjID, "", "pdf", 1);
                            System.out.println("HANDWRITE_DATA不能为空!");
                            thisMsg.put("FILE_MSG", "HANDWRITE_DATA不能为空");
                            return getReturnXml(null, "", beginTime, syntheticPattern, "HANDWRITE_DATA不能为空");
                        }
                        if("0".equals(handType)){
                            //需要解码
                            BASE64Decoder decoder = new BASE64Decoder();
                            byte[] b = decoder.decodeBuffer(htmldata);
                            htmldata = new String(b, "UTF-8");
                            //动态插入节点
                            int insertNote=insertNodeEx(srv_seal,nObjID,"DefSignArea", 3, pageno-1, x_pos, y_pos, node_width, node_height);
                            System.out.println("insertNote:"+insertNote);
                            if(insertNote<=0){
                                System.out.println("插入手写笔迹节点失败,"+insertNote);
                                srv_seal.saveFile(nObjID, "", "pdf",0);
                                thisMsg.put("FILE_MSG", "插入手写笔迹节点失败,"+insertNote);
                                return getReturnXml(null, "", beginTime,syntheticPattern, "插入手写笔迹节点失败,"+insertNote);
                            }
                            int setValue=srv_seal.setValue(nObjID,"DefSignArea", ":PROP:BORDWIDTH:0");
                            System.out.println("setValue:"+setValue);
                            System.out.println("HANDWRITE_DATA:"+htmldata);
                            htmldata=htmldata.substring(htmldata.indexOf(",")+1,htmldata.length()-1);

                            htmldata=htmldata.replaceAll("\r\n", "");
                            htmldata=htmldata.replaceAll("\r", "");
                            htmldata=htmldata.replaceAll("\n", "");
                            int set=srv_seal.setValueEx(nObjID,"DefSignArea",44,0,htmldata);
                            System.out.println("setValueEx:"+set);
                            if(set<=0){
                                ocxSaveFile(srv_seal,nObjID,"", "pdf",1);
                                log.info("error:合成手写数据失败,返回值是："+set+",请确认手写笔迹矢量数据是否正确!");
                                return getReturnXml(null, "", beginTime,syntheticPattern, "error:合成手写数据失败,返回值是："+set+",请确认手写笔迹矢量数据是否正确!");
                            }
                            // int s = srv_seal.saveFile(nObjID, savePath, "pdf");// SrvSealUtil.java

                        }else{//pdf中插入图片
                            int   b  = srv_seal.insertPicture(nObjID,"STRDATA:"+htmldata,pageno-1,x_pos,y_pos,node_width);
                        }
                        String fileNo = (String) thisMsg.get("FILE_NO");
                        log.info("fileNo:" + fileNo);
                        String savePath =upload_path+ filePath4+ "/" + fileNo;
                        log.info("h5合成手写签名的地址为：" + savePath);
                        thisMsg.put("FILE_MSG", "h5合成手写签名成功");
                        int s = ocxSaveFile(srv_seal,nObjID, savePath, "pdf",1);// SrvSealUtil.java
                        System.out.println("makeFilesH5-saveFile:" + s);
                        if(s<0){
                            log.info("h5合成手写签名失败");
                            ocxSaveFile(srv_seal,nObjID,"", "pdf",1);
                            return getReturnXml(null, "", beginTime,syntheticPattern, "error:合成手写数据失败,返回值是："+s);
                        }
                        returnXml = getReturnXml(msgMap, "filePath", beginTime, syntheticPattern);
                    } else {
                        return getReturnXml(null, "", beginTime, syntheticPattern, "文档下载失败");
                    }

                }
            }
            return returnXml;
        } catch (DocumentException e) {
            e.printStackTrace();
            return getReturnXml(null, "", beginTime,null, "xml解析失败");
        }  catch (Exception e) {
            e.printStackTrace();
            return getReturnXml(null, "", beginTime,null, " h5合成手写签名失败");
        }
    }
    /**
     * 得到返回报文（pdf添加水印用）
     *
     * @param map
     * @param folder    合成后文档目录
     * @param beginTime
     * @param checkMsg  验证错误标记
     * @return 响应报文
     */
    private String getReturnXml(Map map, String folder, String beginTime, String... checkMsg) {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
        String retXml = "<?xml version=\"1.0\" encoding=\"utf-8\" ?><WATERMARK_RESPONSE>";
        if (checkMsg.length == 0) {
            retXml += "<META_DATA>";//http://127.0.0.1:
            retXml += "<RET_CODE>" + map.get("RET_CODE") + "</RET_CODE>"
                    + "<FILE_MSG>" + map.get("FILE_MSG") + "</FILE_MSG>"
                    + "<FILE_NO>" + map.get("FILE_NO") + "</FILE_NO>"
                    + "<FILE_URL>" + ("http://" + request.getLocalAddr() + ":" + request.getLocalPort() + "" + Util.getSystemDictionary("server.contextPath") + "/file/" + folder + "?name=" + map.get("FILE_NO"))+ "</FILE_URL>";
            retXml += "</META_DATA>";
        } else {
            retXml += "<RET_CODE>1失败</RET_CODE>"
                    + "<FILE_MSG>" + checkMsg[0] + "</FILE_MSG>"
                    + "<FILE_LIST></FILE_LIST>";
        }
        retXml +="</WATERMARK_RESPONSE>";
        return retXml;
    }
    /**
     * 服务端签章 文档信息保存
     * @param fileNo 文件编号
     * @param creator 文件创建者 存sysid
     * @param creatorName 创建者名称
     * @param filePath 盖章或者合成后文件路径
     * @param sourceType 2 服务端签章, 3 服务端合成
     * @return
     */
    private boolean saveServerDocument(String fileNo,String creator,String creatorName,String filePath,byte sourceType){
        com.dianju.modules.document.models.Document document = new com.dianju.modules.document.models.Document();
        document.setSn(fileNo);//文档号
        document.setCreator(creator);//创建人
        document.setCreatorName(creatorName);//创建人名称
        document.setFilePath(filePath);//文档保存路径
        document.setSourceType(sourceType);//文档类型
        document.setName(fileNo.substring(0,fileNo.lastIndexOf(".")));
        document.setDoStatus((byte)2);//办理状态
        document.setDeptNo("0000000000000000000001");//设置部门号
        try{
            documentDao.save(document);
            return true;
        }catch(Exception e){
            e.printStackTrace();
            return false;
        }

    }

    public String Merge(String fileName,String filePath,String H5Data,HttpServletRequest request) throws UnsupportedEncodingException{

        SrvSealUtil srv_seal = new SrvSealUtil();
        String openPath = filePath;
        //System.out.println("openPath"+openPath);
        String saveType = "pdf";
        String savePath = "D:/ess4java/app/workControl/merge/"+fileName+"."+saveType;
        //String openPath = "D:/ess4java/app/workControl/merge/aaa.aip";
        //String savePath = "D:/ess4java/app/workControl/merge/aaa.pdf";
        //H5Data = "<0,800,1100,#ff0000(274,105,5;278,108,5;283,113,5;290,119,5;300,126,5;311,134,5;324,142,5;340,151,5;354,160,5;354,160,5;367,169,5;377,174,5;382,178,5;384,179,5;385,180,5;380,180,5;))(225,222,5;237,223,5;252,224,5;268,224,5;292,224,5;322,224,5;355,224,5;388,224,5;418,224,5;447,224,5;477,224,5;499,224,5;516,224,5;528,224,5;534,224,5;537,224,5;538,224,5;538,225,5;536,226,5;))(240,237,5;245,245,5;249,254,5;254,266,5;262,286,5;268,308,5;274,337,5;281,367,5;286,401,5;289,434,5;291,465,5;289,491,5;284,514,5;277,534,5;269,552,5;260,565,5;252,574,5;244,581,5;237,585,5;233,587,5;232,588,5;232,587,5;235,581,5;241,574,5;248,565,5;))(362,427,5;366,425,5;373,424,5;384,422,5;400,418,5;423,414,5;450,409,5;476,403,5;503,398,5;526,394,5;545,390,5;557,387,5;565,383,5;570,381,5;572,378,5;572,377,5;571,373,5;567,369,5;560,365,5;))(436,309,5;438,316,5;441,325,5;445,341,5;449,364,5;456,391,5;461,423,5;466,454,5;469,482,5;470,508,5;470,522,5;470,539,5;468,548,5;466,555,5;464,559,5;464,560,5;463,560,5;462,560,5;460,560,5;458,559,5;))(390,577,5;395,576,5;404,574,5;415,572,5;428,568,5;446,565,5;469,560,5;496,555,5;522,551,5;547,549,5;567,548,5;582,548,5;592,548,5;599,548,5;601,548,5;602,548,5;603,548,5;))>";

        //base64解码
        Decoder decoder = Base64.getDecoder();
        byte[] bytes = decoder.decode(H5Data);
        String dataH5 = new String(bytes);


        int nObjID = ocxOpenObj(srv_seal,openPath, 0, 0);
        if(nObjID<=0){
            System.out.println("-911");
            return "-911";
        }

        int loginret = ocxLogin(srv_seal, nObjID, 2, "dj","");
        if(loginret!=0){
            ocxSaveFile(srv_seal,nObjID, "", saveType, 0);
            System.out.println("-909");
        }

        int setRet = srv_seal.setValue(nObjID, "SET_PEN_DATA_DIRECTLY", dataH5);//合成H5手写数据

        if(setRet!=1){
            ocxSaveFile(srv_seal,nObjID, "", saveType,0);
            System.out.println("-911");
            return "-911";
        }

        int s = ocxSaveFile(srv_seal,nObjID, savePath, saveType, 0);
        if (s != 1) {
            System.out.println("-914");
            return "-914";
        }
        //return "111";
        return "http://" + request.getLocalAddr() + ":" + request.getLocalPort() +Util.getSystemDictionary("server.contextPath")+"/file/merge?name="+fileName+"."+saveType;

    }


    //0为成功，其他为失败
    private static int ocxLogin(SrvSealUtil srv_seal,int nObjID, int nLoginType, String userID,String pwd){
        if (System.getProperty("os.name").toUpperCase().indexOf("WINDOWS") != -1) {
            int loginret = srv_seal.login(nObjID, nLoginType, userID,pwd);
            return loginret;
        }else{
            int loginret = srv_seal.login(nObjID, userID, nLoginType,pwd);
            //linux下1是成功，0是失败
            if(loginret==1){
                return 0;
            }else{
                if(loginret!=0){
                    return loginret;
                }else{
                    return -1000;
                }
            }
//  			return 0;
        }
    }

    private static int ocxOpenObj(SrvSealUtil srv_seal,String openPath, int nFS1, int nFS2){
//  		System.out.println("ocxOpenObj");
        if (System.getProperty("os.name").toUpperCase().indexOf("WINDOWS") != -1) {
            int nObjID = srv_seal.openObj(openPath, 0, 0);
            return nObjID;
        }else{
//  			System.out.println("Linux");
            int nObjID = srv_seal.openObj(openPath, 0);
            return nObjID;
        }
    }


    private static int ocxSaveFile(SrvSealUtil srv_seal,int nObjID, String savePath, String type, int keepObj){
//  		System.out.println("ocxSaveFile");
        if (System.getProperty("os.name").toUpperCase().indexOf("WINDOWS") != -1) {
            int s = srv_seal.saveFile(nObjID, savePath, type, 0);
            return s;
        }else{
//  			System.out.println("Linux");
            int s = srv_seal.saveFile(nObjID, savePath);
            return s;
        }
    }


    private static int insertNodeEx(SrvSealUtil srv_seal,int nObjID, String noteName, int noteType, int pageIndex, int x, int y, int w, int h){
//  		System.out.println("insertNodeEx");
        if (System.getProperty("os.name").toUpperCase().indexOf("WINDOWS") != -1) {
            int insertNote = srv_seal.insertNodeEx(nObjID,noteName,noteType, pageIndex,x, y, w,h);
            return insertNote;
        }else{
//  			System.out.println("Linux");
            int insertNote = srv_seal.insertNote(nObjID,noteName, noteType,pageIndex,x,  y,w,h);
            return insertNote;
        }
    }

    private  SrvSealUtil srv_seal(){
        if (srvSealUtil == null) {
            srvSealUtil = (SrvSealUtil) Util.getBean("srvSealUtil");
        }
        return   srvSealUtil;
    }
    private SrvSealUtil srvSealUtil;
    private String path = null;
    private String filePath = null;
    private String sealFilePath = null;
    private String syntheticType = null;
    protected String fileToPicture=null;
    private Map<String,String> documentInfo = new HashMap<>();//文档信息
    //@Value("${server.contextPath}")
    private String contextPath = "/";
    @Autowired
    private LogServerSealDao logServerSealDao;
    @Autowired
    private LogFileServerSealDao logFileServerSealDao;
    @Autowired
    private SealDao sealDao;
    @Autowired
    private DocumentDao documentDao;

    @Autowired
    private DJPdfToImgUtil djPdfToImgUtil;
}
