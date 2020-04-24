package com.oracle.truffle.js.runtime;

import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.object.Property;
import com.oracle.truffle.js.nodes.promise.PromiseReactionJobNode;
import com.oracle.truffle.js.runtime.objects.JSProperty;
import com.oracle.truffle.js.runtime.objects.PromiseReactionRecord;

import java.io.*;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

public class RecordAndReplay {
    private static RecordAndReplay INSTANCE;
    private static final boolean TRACING = System.getProperty("tracing","false").compareTo("true") == 0;
    private static final boolean REPLAY = System.getProperty("replay","false").compareTo("true") == 0;
    private static final String FILE = System.getProperty("file");

    public static synchronized RecordAndReplay getInstance(){
        if(INSTANCE == null) {
            INSTANCE = new RecordAndReplay();
        }
        return INSTANCE;
    }

    private List<Integer> orderList = new ArrayList<Integer>();
    private PrintStream serializerStream;

    private RecordAndReplay() {
        //TRACING
        if(TRACING) {
            String file = FILE;
            if (file == null) {
                file = "serialized.tracing";
            }

            try {
                serializerStream = new PrintStream(new FileOutputStream(file));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        //REPLAY
        if(REPLAY) {
            String file = FILE;
            if (file == null) {
                file = "serialized.tracing";
            }

            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file)));
                orderList = reader.lines()
                        .mapToInt(x -> Integer.parseInt(x))
                        .boxed()
                        .collect(Collectors.toList());

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    public void serialize(String text) {
        if(TRACING) {
            serializerStream.println(text);
        }
    }

    public void serialize(DynamicObject job) {
        if(TRACING) {
            serializerStream.println(getIdentifier(job));
        }
    }

    public int getIdentifier(DynamicObject job) {
        Property prop = job.getShape().getProperty(PromiseReactionJobNode.REACTION_KEY);
        PromiseReactionRecord prr = (PromiseReactionRecord) JSProperty.getValue(prop,job, job,false);
        return prr.getCapability().getId();
    }

    public boolean reorganizeJobQueue(Deque<DynamicObject> jobQueue) {
        if (REPLAY && orderList.size() > 0) {

            int nextElemId = orderList.get(0);
            if (getIdentifier(jobQueue.getLast()) != nextElemId) {
                if(jobQueue.size() <= 1) {
                    return false;
                } else {
                    if(!reOrder(jobQueue, nextElemId)){
                        return false;
                    }
                }
            }
            orderList.remove(0);
        }
        return true;
    }

    private boolean reOrder(Deque<DynamicObject> jobQueue, int searchId) {
        DynamicObject found = null;
        for(DynamicObject d : jobQueue){
            if(getIdentifier(d) == searchId) {
                found = d;
                break;
            }
        }
        if(found != null && jobQueue.remove(found)){
            jobQueue.addLast(found);
            return true;
        } else {
            return false;
        }
    }

}
