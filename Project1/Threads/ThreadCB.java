package osp.Threads;

import java.util.LinkedList;
import java.util.Queue;
import java.util.Vector;
import java.util.Enumeration;
import osp.Utilities.*;
import osp.IFLModules.*;
import osp.Tasks.*;
import osp.EventEngine.*;
import osp.Hardware.*;
import osp.Devices.*;
import osp.Memory.*;
import osp.Resources.*;

/**
   This class is responsible for actions related to threads, including
   creating, killing, dispatching, resuming, and suspending threads.

   @OSPProject Threads
*/
public class ThreadCB extends IflThreadCB 
{
    private static ReadyQueue readyQueue;
    private int clock; //to count how many times it is dispatched
    /**
       The thread constructor. Must call 

       	   super();

       as its first statement.

       @OSPProject Threads
    */
    public ThreadCB()
    {
        super();
        clock = 0;
    }

    /**
       This method will be called once at the beginning of the
       simulation. The student can set up static variables here.
       
       @OSPProject Threads
    */
    public static void init()
    {
        readyQueue = new ReadyQueue();
    }

    /** 
        Sets up a new thread and adds it to the given task. 
        The method must set the ready status 
        and attempt to add thread to task. If the latter fails 
        because there are already too many threads in this task, 
        so does this method, otherwise, the thread is appended 
        to the ready queue and dispatch() is called.

	The priority of the thread can be set using the getPriority/setPriority
	methods. However, OSP itself doesn't care what the actual value of
	the priority is. These methods are just provided in case priority
	scheduling is required.

	@return thread or null

        @OSPProject Threads
    */
    static public ThreadCB do_create(TaskCB task)
    {
        //check if maximum exceeds or if task is null
        if(task ==null || task.getThreadCount()>=MaxThreadsPerTask)
        {
            dispatch();
            return null;
        }

        ThreadCB thread = new ThreadCB();
        thread.setTask(task);
        thread.setStatus(ThreadReady);
        if (task.addThread(thread) == FAILURE)
        {
            dispatch();
            return null;
        }

        readyQueue.add(thread);

        dispatch();

        return thread;
    }

    /** 
	Kills the specified thread. 

	The status must be set to ThreadKill, the thread must be
	removed from the task's list of threads and its pending IORBs
	must be purged from all device queues.
        
	If some thread was on the ready queue, it must removed, if the 
	thread was running, the processor becomes idle, and dispatch() 
	must be called to resume a waiting thread.
	
	@OSPProject Threads
    */
    public void do_kill()
    {
        int currentState = getStatus();
        TaskCB task = getTask();

        //check current state
        switch (currentState)
        {
            case ThreadReady://remove from ready queue
                readyQueue.remove(this);
                break;
            case ThreadRunning://lose control of cpu
                MMU.setPTBR(null);
                task.setCurrentThread(null);
                break;
            case ThreadWaiting:
                break;
        }

        //remove killed thread from task
        task.removeThread(this);

        //cancel corresponding IORB
        for (int i = 0; i<Device.getTableSize();i++)
        {
            Device.get(i).cancelPendingIO(this);
        }

        //give up corresponding shared resource
        ResourceCB.giveupResources(this);

        setStatus(ThreadKill);

        dispatch();

        //kill empty task if necessary
        if(task.getThreadCount() == 0)
            task.kill();
    }

    /** Suspends the thread that is currenly on the processor on the 
        specified event. 

        Note that the thread being suspended doesn't need to be
        running. It can also be waiting for completion of a pagefault
        and be suspended on the IORB that is bringing the page in.
	
	Thread's status must be changed to ThreadWaiting or higher,
        the processor set to idle, the thread must be in the right
        waiting queue, and dispatch() must be called to give CPU
        control to some other thread.

	@param event - event on which to suspend this thread.

        @OSPProject Threads
    */
    public void do_suspend(Event event)
    {
        int currentState = getStatus();
        TaskCB task = getTask();

        if(currentState == ThreadRunning)//lose control to cpu
        {
            MMU.setPTBR(null);
            task.setCurrentThread(null);
            setStatus(ThreadWaiting);
        }
        else if(currentState >= ThreadWaiting)//increment the waiting status by 1
        {
            setStatus(getStatus()+1);
        }

        event.addThread(this);//add to the waiting queue

        dispatch();
    }

    /** Resumes the thread.
        
	Only a thread with the status ThreadWaiting or higher
	can be resumed.  The status must be set to ThreadReady or
	decremented, respectively.
	A ready thread should be placed on the ready queue.
	
	@OSPProject Threads
    */
    public void do_resume()
    {
        if(getStatus() < ThreadWaiting)//no
            return;
        else if(getStatus() == ThreadWaiting){//put it in ready queue
            setStatus(ThreadReady);
            readyQueue.add(this);
        }
        else {
            setStatus(getStatus()-1);//decrement the waiting status by 1
        }

        dispatch();
    }

    /** 
        Selects a thread from the run queue and dispatches it. 

        If there is just one thread ready to run, reschedule the thread
        currently on the processor.

        In addition to setting the correct thread status it must
        update the PTBR.
	
	@return SUCCESS or FAILURE

        @OSPProject Threads
    */
    public static int do_dispatch()
    {
        try{//preempt if available
            TaskCB task = MMU.getPTBR().getTask();
            ThreadCB thread = task.getCurrentThread();

            if(thread!=null)//preempt running thread and put it into ready queue
            {
                MMU.setPTBR(null);
                task.setCurrentThread(null);
                thread.setStatus(ThreadReady);
                readyQueue.add(thread);
            }
        }catch (NullPointerException e) {}

        //dispatch
        ThreadCB nextTread = readyQueue.remove();
        if (nextTread==null||nextTread.getStatus()!=ThreadReady) return FAILURE; //the ready queue is empty

        TaskCB nextTask = nextTread.getTask();

        nextTread.setClock(nextTread.getClock() + 1);//increment the clock by 1 every time being dispatched
        MMU.setPTBR(nextTask.getPageTable());
        nextTask.setCurrentThread(nextTread);
        nextTread.setStatus(ThreadRunning);

        return SUCCESS;

    }

    /**
       Called by OSP after printing an error message. The student can
       insert code here to print various tables and data structures in
       their state just after the error happened.  The body can be
       left empty, if this feature is not used.

       @OSPProject Threads
    */
    public static void atError()
    {
        // your code goes here
    }

    /** Called by OSP after printing a warning message. The student
        can insert code here to print various tables and data
        structures in their state just after the warning happened.
        The body can be left empty, if this feature is not used.
       
        @OSPProject Threads
     */
    public static void atWarning()
    {
        //showTrace();

    }

    public int getClock()
    {
        return this.clock;
    }

    public void setClock(int clock)
    {
        this.clock = clock;
    }

    //Cited from google, for debug only
    /*public static void showTrace()
    {
        System.out.println("Trace: " +
                "file " + new Throwable().getStackTrace()[1].getFileName() +
                " class " + new Throwable().getStackTrace()[1].getClassName() +
                " method " + new Throwable().getStackTrace()[1].getMethodName() +
                " line " + new Throwable().getStackTrace()[1].getLineNumber());
    }*/

}

class ReadyQueue
{
    private static int cycle;//determine invocation on which queue

    private Queue<ThreadCB> Q1;
    private Queue<ThreadCB> Q2;


    static
    {
        cycle = 1;
    }

    public ReadyQueue()
    {
        Q1 = new LinkedList<>();
        Q2 = new LinkedList<>();
    }

    public ThreadCB remove()//remove head
    {
        int currentCycle = (cycle++)%3;

        if (currentCycle==1 || currentCycle==2){
            if(Q1.isEmpty())//check if current queue empty
            {
                if(Q2.isEmpty())//check another queue
                    return null;
                return Q2.remove();
            }
            return Q1.remove();
        }
        else
            {
            if(Q2.isEmpty())//check if current queue empty
            {
                if(Q1.isEmpty())//check another queue
                    return null;
                return Q1.remove();
            }
            return Q2.remove();
        }
    }

    public void remove(ThreadCB thread)//remove particular thread
    {
        Q1.remove(thread);
        Q2.remove(thread);
    }

    public void add(ThreadCB thread)
    {
        if(thread.getClock()>=7)//demote to Q2, and always stay there
            Q2.add(thread);
        else// it is still considered as short live thread
            Q1.add(thread);
    }

    public void printQ()//for debugging purpose
    {
        if(!Q1.isEmpty() && !Q2.isEmpty())
            System.out.println("Q1: " + Q1+"\nQ2: "+Q2+"\n---------------");
        if(Q1.isEmpty() && !Q2.isEmpty())
            System.out.println("Q1 is empty " + "\nQ2: "+Q2+"\n---------------");
        if(!Q1.isEmpty() && Q2.isEmpty())
            System.out.println("Q1: " + Q1+"\nQ2 is empty"+"\n---------------");
        if(Q1.isEmpty() && Q2.isEmpty())
            System.out.println("Both are empty\n---------------");
    }


}
