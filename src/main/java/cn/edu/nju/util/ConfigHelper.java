package cn.edu.nju.util;

import cn.edu.nju.builder.AbstractCheckerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import static cn.edu.nju.util.FileHelper.*;
/**
 * Created by njucjc at 2020/2/7
 */
public class ConfigHelper {
    public static Properties getConfig(String configFile) {
        if (!isFileExists(configFile)) {
            System.out.println("[INFO] 配置文件不存在: " + configFile);
            System.exit(1);
        }

        Properties properties = new Properties();
        try {
            FileInputStream fis = new FileInputStream(configFile);
            properties.load(fis);
            fis.close();
        }catch (IOException e) {
            e.printStackTrace();
        }

        return properties;
    }

    public static void setConfig(Properties properties, String path) {
        try {

            File file = new File(path);
            if (file.exists()) {
                file.delete();
            }

            FileOutputStream oFile = new FileOutputStream(path, true);
            properties.store(oFile, "The New properties file");
            oFile.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
