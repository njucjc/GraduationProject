package cn.edu.nju.context;

import cn.edu.nju.builder.AbstractCheckerBuilder;

/**
 * Created by njucjc on 2017/10/23.
 */
public class ContextParser {
    public Context parseContext(int id, String pattern) {
        String [] fields = pattern.split(",");
        if (fields.length < 13) {
            System.out.println("[INFO] '"+ AbstractCheckerBuilder.dataFilePath + "'文件格式错误");
            System.exit(1);
        }

        String type = fields[0];
        String ip = fields[1];
        String location = fields[2];
        String generalState = fields[3];
        String powerState = fields[4];
        String fanState = fields[5];
        String portState = fields[6];
        double CPUUsage = Double.parseDouble(fields[7]);
        double CPUTemp = Double.parseDouble(fields[8]);
        double memUsage = Double.parseDouble(fields[9]);
        double closetTemp = Double.parseDouble(fields[10]);
        String timestamp = fields[11];
        int order = Integer.parseInt(fields[12]);

        return new Context(id, type, ip, location, generalState, powerState, fanState, portState, CPUUsage, CPUTemp, memUsage, closetTemp, timestamp, order);
    }

    public Context parseChangeContext(String [] elements) {

        if (elements.length < 16) {
            System.out.println("[INFO] '"+ AbstractCheckerBuilder.changeFilePath + "'文件格式错误");
            System.exit(1);
        }
        Context context = null;
        try {
            context = new Context(Integer.parseInt(elements[2]),
                    elements[3],
                    elements[4],
                    elements[5],
                    elements[6],
                    elements[7],
                    elements[8],
                    elements[9],
                    Double.parseDouble(elements[10]),
                    Double.parseDouble(elements[11]),
                    Double.parseDouble(elements[12]),
                    Double.parseDouble(elements[13]),
                    elements[14],
                    Integer.parseInt(elements[15]));
        } catch (NumberFormatException e) {
            System.out.println("[INFO] '"+ AbstractCheckerBuilder.changeFilePath + "'文件格式错误");
            System.exit(1);
        }

        return context;
    }

    public static String updateTime(String str, String time) {
        String [] strs = str.split(",");
        if(strs[0].equals("+") || strs[0].equals("-")) {
            strs[14] = time;
        }
        else {
            strs[11] = time;
        }
        return String.join(",", strs);
    }
}
