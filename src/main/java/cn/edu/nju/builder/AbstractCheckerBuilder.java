package cn.edu.nju.builder;

import cn.edu.nju.change.*;
import cn.edu.nju.checker.*;
import cn.edu.nju.memory.GPUContextMemory;
import cn.edu.nju.memory.GPUResult;
import cn.edu.nju.node.Param;
import cn.edu.nju.node.STNode;
import cn.edu.nju.pattern.Pattern;
import cn.edu.nju.scheduler.BatchScheduler;
import cn.edu.nju.scheduler.GEASOptScheduler;
import cn.edu.nju.scheduler.GEAScheduler;
import cn.edu.nju.scheduler.Scheduler;
import cn.edu.nju.switcher.SimpleSwitcher;
import cn.edu.nju.switcher.Switcher;
import cn.edu.nju.util.LogFileHelper;
import cn.edu.nju.util.PTXFileHelper;
import jcuda.driver.CUcontext;
import jcuda.driver.CUdevice;
import jcuda.driver.JCudaDriver;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static jcuda.driver.JCudaDriver.*;

public abstract class AbstractCheckerBuilder implements CheckerType{



    protected List<Checker> checkerList;

    /*调度checker的策略*/
    protected Scheduler scheduler;

    protected String dataFilePath; //context data

    protected String changeFilePath; //context change

    /*所有pattern*/
    protected Map<String, Pattern> patternMap;

    protected Map<String, Checker> checkerMap;

    private int checkType = ECC_TYPE;

    private int scheduleType;


    private int taskNum;

    private ExecutorService checkExecutorService;

    protected ChangeHandler changeHandler;

    protected String changeHandlerType;

    private String kernelFilePath;

    private CUcontext cuContext;

    private GPUResult gpuResult;

    private List<String> contexts;

    protected boolean onDemand;

    protected Switcher switcher;

    public AbstractCheckerBuilder(String configFilePath) {
        parseConfigFile(configFilePath);
    }

    private void parseConfigFile(String configFilePath) {
        //不要随意更换处理顺序
        Properties properties = new Properties();
        try {
            FileInputStream fis = new FileInputStream(configFilePath);
            properties.load(fis);
            fis.close();
        }catch (IOException e) {
            e.printStackTrace();
        }

        //check type
        String technique = properties.getProperty("technique");
        if("PCC".equals(technique)) {
            this.checkType = PCC_TYPE;
        } else if("ECC".equals(technique)){
            this.checkType = ECC_TYPE;
        } else if("Con-C".equals(technique)) {
            this.checkType = CON_TYPE;
        } else if("GAIN".equals(technique)) {
            this.checkType = GAIN_TYPE;
        } else if ("Con-PCC".equals(technique)) {
            this.checkType = CONPCC_TYPE;
        }else {
            assert false:"[DEBUG] Checking technique error: " + technique;
        }

        //taskNum
        String taskNumStr = properties.getProperty("taskNum");
        if(taskNumStr.matches("[0-9]+")) {
            this.taskNum = Integer.parseInt(taskNumStr);
            if (taskNum == 0) {
                assert false:"[DEBUG] taskNum error: " + taskNumStr;
            }
            System.out.println("[DEBUG] " + taskNum);
        } else {
            assert false:"[DEBUG] taskNum error: " + taskNumStr;
        }

        this.checkExecutorService = Executors.newFixedThreadPool(taskNum);
        //pattern
        String patternFilePath = properties.getProperty("patternFilePath");
        parsePatternFile(patternFilePath);

        String cudaSourceFilePath = properties.getProperty("cudaSourceFilePath");
        //如果是GAIN需要初始化GPU内存

        //context file path
        this.dataFilePath = properties.getProperty("dataFilePath");
        this.changeFilePath = properties.getProperty("changeFilePath");

        if(this.checkType == GAIN_TYPE) {
            //开启异常捕获
            JCudaDriver.setExceptionsEnabled(true);

            //初始化设备
            cuInit(0);
            CUdevice device = new CUdevice();
            cuDeviceGet(device, 0);
            cuContext = new CUcontext();
            cuCtxCreate(cuContext, 0, device);

           // initGPUMemory();
            contexts = fileReader(this.dataFilePath);
            gpuResult = new GPUResult();
            compileKernelFunction(cudaSourceFilePath);
        }

        //rule
        String ruleFilePath = properties.getProperty("ruleFilePath");
        parseRuleFile(ruleFilePath);


        //log
        String logFilePath = properties.getProperty("logFilePath");
        LogFileHelper.initLogger(logFilePath);


        //schedule
        String schedule = properties.getProperty("schedule");
        //change handler
        this.changeHandlerType = properties.getProperty("changeHandlerType");
        if(schedule.matches("[0-9]+")) {
            this.scheduler = new BatchScheduler(Integer.parseInt(schedule));
            this.scheduleType = Integer.parseInt(schedule);
            System.out.println("[DEBUG] " + schedule);
        }
        else if ("GEAS".equals(schedule) && this.changeHandlerType.contains("change-based")) {
            this.scheduler = new GEAScheduler(this.checkerList);
            this.scheduleType = 0;
            System.out.println("[DEBUG] " + schedule);
        }
        else if ("GEAS-opt".equals(schedule) && this.changeHandlerType.contains("change-based")) {
            this.scheduler = new GEASOptScheduler(this.checkerList);
            this.scheduleType = -2;
            System.out.println("[DEBUG] " + schedule);
        }
        else {
            this.scheduleType = -1;
            assert false:"[DEBUG] Schedule error: " + schedule;
        }

        //change handle
        configChangeHandler();

        String onDemandStr = properties.getProperty("on-demand");
        if("on".equals(onDemandStr)) {
            this.onDemand = true;
        }
        else {
            this.onDemand = false;
        }

        String paramFilePath = properties.getProperty("paramFilePath");
        this.switcher = new SimpleSwitcher(paramFilePath, this.checkType, this.scheduleType);
    }

    private void configChangeHandler() {
        if(this.changeHandlerType.contains("time-based")) {
            this.changeHandler = new TimebasedChangeHandler(patternMap, checkerMap, scheduler, checkerList);
        }
        else if(this.changeHandlerType.contains("change-based")) {
            this.changeHandler = new ChangebasedChangeHandler(patternMap, checkerMap, scheduler, checkerList);
        }
    }

    private void parsePatternFile(String patternFilePath) {
        this.patternMap = new ConcurrentHashMap<>();
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        try {
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document document = db.parse(patternFilePath);

            NodeList patternList = document.getElementsByTagName("pattern");
            System.out.println("[DEBUG] There is " + patternList.getLength() + " patterns");
            for (int i = 0; i < patternList.getLength(); i++) {
                Node patNode = patternList.item(i);
                NodeList childNodes = patNode.getChildNodes();

                String id = childNodes.item(1).getTextContent();
                int freshness = Integer.parseInt(childNodes.item(3).getTextContent());
                String category = childNodes.item(5).getTextContent();
                String subject = childNodes.item(7).getTextContent();
                String predicate = childNodes.item(9).getTextContent();
                String object = childNodes.item(11).getTextContent();
                String site = childNodes.item(13).getTextContent();

                patternMap.put(id, new Pattern(id, freshness, category, subject, predicate, object, site));
            }

            for(String key : patternMap.keySet()) {
                System.out.println("[DEBUG] " + patternMap.get(key));
            }
        }
        catch (ParserConfigurationException e) {
            e.printStackTrace();
        } catch (SAXException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void parseRuleFile(String ruleFilePath) {
        this.checkerList = new CopyOnWriteArrayList<>();
        this.checkerMap = new ConcurrentHashMap<>();
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();

        try {
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document document = db.parse(ruleFilePath);

            NodeList ruleList = document.getElementsByTagName("rule");
            System.out.println("[DEBUG] There is " + ruleList.getLength() + " rules here.");

            for(int i = 0; i < ruleList.getLength(); i++){
                STNode treeHead = new STNode();

                Node ruleNode = ruleList.item(i);

                Node idNode = ruleNode.getChildNodes().item(1);
                Node formulaNode = ruleNode.getChildNodes().item(3);

                Map<String,STNode> stMap = new HashMap<>();
                buildSyntaxTree(formulaNode.getChildNodes(), treeHead, stMap);

                assert treeHead.hasChildNodes():"[DEBUG] Create syntax tree failed !";

                STNode root = (STNode)treeHead.getFirstChild();
                root.setParentTreeNode(null);

                Checker checker = null;
                if(checkType == PCC_TYPE) {
                    checker = new PccChecker(idNode.getTextContent(), root, this.patternMap, stMap);
                }
                else if (checkType == ECC_TYPE){
                    checker = new EccChecker(idNode.getTextContent(), root, this.patternMap, stMap);
                } else if(checkType == CON_TYPE){ //CON-C
                    checker = new ConChecker(idNode.getTextContent(), root, this.patternMap, stMap, taskNum, checkExecutorService);
                } else if(checkType == GAIN_TYPE) {
                    checker = new GAINChecker(idNode.getTextContent(), root, this.patternMap, stMap,
                            kernelFilePath, //kernel function
                            contexts, cuContext, gpuResult); //GPU memory
                } else if(checkType == CONPCC_TYPE) {
                    checker = new ConPccChecker(idNode.getTextContent(), root, this.patternMap, stMap, taskNum, checkExecutorService);
                }

                checkerList.add(checker);
                for(String key : stMap.keySet()) {
                    checkerMap.put(stMap.get(key).getContextSetName(), checker);
                }

                System.out.println("[DEBUG] " + checker.getName());
                checker.printSyntaxTree();
            }
        } catch (ParserConfigurationException e) {
            e.printStackTrace();
        } catch (SAXException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    protected List<String> fileReader(String filePath) {
        List<String> list = new ArrayList<>();
        try {
            FileReader fr = new FileReader(filePath);
            BufferedReader bufferedReader = new BufferedReader(fr);

            String change;
            while ((change = bufferedReader.readLine()) != null) {
                list.add(change);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        return list;
    }

    private void buildSyntaxTree(NodeList list, STNode root, Map<String,STNode> stMap) {
        for(int i = 0; i < list.getLength(); i++) {
            if (list.item(i).getNodeType() == Node.ELEMENT_NODE && !list.item(i).getNodeName().equals("param")) {
                Element e = (Element)list.item(i);
                STNode stNode = null;
                String nodeName = e.getNodeName();

                switch (nodeName) {
                    case "forall":
                        stNode = new STNode(nodeName, STNode.UNIVERSAL_NODE, e.getAttribute("in"));
                        stMap.put(e.getAttribute("in"), stNode);
                        break;
                    case "exists":
                        stNode = new STNode(nodeName, STNode.EXISTENTIAL_NODE, e.getAttribute("in"));
                        stMap.put(e.getAttribute("in"),stNode);
                        break;
                    case "and":
                        stNode = new STNode(nodeName, STNode.AND_NODE);
                        break;
                    case "not":
                        stNode = new STNode(nodeName, STNode.NOT_NODE);
                        break;
                    case "implies":
                        stNode = new STNode(nodeName, STNode.IMPLIES_NODE);
                        break;
                    case "bfunction":
                        stNode = new STNode(e.getAttribute("name"), STNode.BFUNC_NODE, buildParam(e));
                        break;
                    default:
                        assert false : "[DEBUG] Syntax tree node type error!";
                        break;
                }

                buildSyntaxTree(e.getChildNodes(), stNode, stMap);
                root.addChildeNode(stNode);
            }
        }
    }

    private List<Param> buildParam(Element e) {
        List<Param> res = new ArrayList<>();
        NodeList list = e.getChildNodes();
        for (int i = 1; i < list.getLength(); i+= 2) {
            Element element = (Element) list.item(i);
            int pos = Integer.parseInt(element.getAttribute("pos"));
            String op = element.getAttribute("op");
            String defaultValue = element.getAttribute("default_value");
            res.add(new Param(pos, op, defaultValue));
        }
        return res;
    }

    private void compileKernelFunction(String cudaSourceFilePath) {

        try {
            this.kernelFilePath = PTXFileHelper.preparePtxFile(cudaSourceFilePath);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void shutdown() {
        checkExecutorService.shutdown();
        if(checkType == GAIN_TYPE) {
            GPUContextMemory.getInstance(contexts).free();
            for (Checker checker : checkerList) {
                checker.reset();
            }
        }
    }

    protected void update(int checkType, int scheduleType) {
        boolean isUpdate = false;

        //update checkers
        if(this.checkType != checkType) {
            isUpdate = true;
            List<Checker> curList = new CopyOnWriteArrayList<>();
            Map<String, Checker> curMap = new ConcurrentHashMap();

            for(Checker checker : checkerList) {

                Checker c = null;
                if(checkType == PCC_TYPE) {
                    c = new PccChecker(checker);
                }
                else if(checkType == ECC_TYPE) {
                    c = new EccChecker(checker);
                }
                else if(checkType == CON_TYPE) {
                    c = new ConChecker(checker, taskNum, this.checkExecutorService);
                }
                else if(checkType == CONPCC_TYPE) {
                    c = new ConPccChecker(checker, taskNum, this.checkExecutorService);
                }
                else {
                    assert false:"Type Error.";
                }

                curList.add(c);

                Map<String, STNode> stMap = checker.getStMap();
                for(String key : stMap.keySet()) {
                    curMap.put(stMap.get(key).getContextSetName(), c);
                }
                checker.reset();
            }
            this.checkType = checkType;
            this.checkerList = curList;
            this.checkerMap = curMap;
        }

        //update scheduler
        if(scheduleType != this.scheduleType) {
            isUpdate = true;
            if(scheduleType == 0) {
                this.scheduler = new GEAScheduler(this.checkerList);
            }
            else {
                this.scheduler = new BatchScheduler(scheduleType);
            }

            this.scheduleType = scheduleType;
        }

        if(isUpdate) {
            this.changeHandler.update(this.checkerMap,this.scheduler, this.checkerList);
        }
    }


    protected int computeWorkload() {
        int workload = 0;
        for(Checker checker: checkerList) {
            workload += checker.getWorkload();
        }
        return  workload;
    }
}
