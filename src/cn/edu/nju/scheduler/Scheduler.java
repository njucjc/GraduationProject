package cn.edu.nju.scheduler;

/**
 * Created by njucjc on 2017/10/23.
 */
public interface Scheduler {
    void  update();
    boolean schedule();
}