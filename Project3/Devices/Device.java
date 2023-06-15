package osp.Devices;

/*
* Haoyu Lu
* 111308297
* I pledge my honor that all parts of this project were done by me individually,
without collaboration with anyone, and without consulting any external sources
that provide full or partial solutions to a similar project.
I understand that breaking this pledge will result in an “F” for the entire
course.
*/

/**
    This class stores all pertinent information about a device in
    the device table.  This class should be sub-classed by all
    device classes, such as the Disk class.

    @OSPProject Devices
*/

import osp.Devices.IORB;
import osp.IFLModules.*;
import osp.Threads.*;
import osp.Utilities.*;
import osp.Hardware.*;
import osp.Memory.*;
import osp.FileSys.*;
import osp.Tasks.*;

import java.lang.reflect.Array;
import java.util.*;

public class Device extends IflDevice
{
    /**
        This constructor initializes a device with the provided parameters.
	As a first statement it must have the following:

	    super(id,numberOfBlocks);

	@param numberOfBlocks -- number of blocks on device

        @OSPProject Devices
    */
    private static List<Long> clocks;

    public Device(int id, int numberOfBlocks)
    {
        super(id, numberOfBlocks);
        iorbQueue = new GenericList();
        ((Disk)this).setHeadPosition(0);
    }

    /**
       This method is called once at the beginning of the
       simulation. Can be used to initialize static variables.

       @OSPProject Devices
    */
    public static void init()
    {
        clocks = new ArrayList<Long>();
    }

    /**
       Enqueues the IORB to the IORB queue for this device
       according to some kind of scheduling algorithm.
       
       This method must lock the page (which may trigger a page fault),
       check the device's state and call startIO() if the 
       device is idle, otherwise append the IORB to the IORB queue.

       @return SUCCESS or FAILURE.
       FAILURE is returned if the IORB wasn't enqueued 
       (for instance, locking the page fails or thread is killed).
       SUCCESS is returned if the IORB is fine and either the page was 
       valid and device started on the IORB immediately or the IORB
       was successfully enqueued (possibly after causing pagefault pagefault)
       
       @OSPProject Devices
    */
    public int do_enqueueIORB(IORB iorb)
    {
        if(iorb.getThread().getStatus()==ThreadKill)
            return FAILURE;

        GenericList IorbQueue = (GenericList)iorbQueue;
        Disk disk = (Disk)this;
        iorb.getPage().lock(iorb);//lock the page associated with the iorb
        iorb.getOpenFile().incrementIORBCount();//increment the openFile

        if(iorb.getThread().getStatus()==ThreadKill)
            return FAILURE;

        int blockSize = (int)Math.pow(2, (MMU.getVirtualAddressBits()-MMU.getPageAddressBits()));
        int sectorNumPerBlock = blockSize/disk.getBytesPerSector();
        int blockNumPerTrack = disk.getSectorsPerTrack()/sectorNumPerBlock;
        int trackNumPerCylinder =  disk.getPlatters();
        int cylinderId = iorb.getBlockNumber()/(blockNumPerTrack * trackNumPerCylinder);
        iorb.setCylinder(cylinderId);//set iorb's cylinder ID

        if(iorb.getThread().getStatus()==ThreadKill)
            return FAILURE;

        if(this.isBusy()){
            IorbQueue.append(iorb);//add to device queue
            clocks.add(HClock.get());//mark the time when iorb is enqueued - SSTF
        }
        else
            this.startIO(iorb);//if idle start I/O operation

        return SUCCESS;
    }

    /**
       Selects an IORB (according to some scheduling strategy)
       and dequeues it from the IORB queue.

       @OSPProject Devices
    */
    public IORB do_dequeueIORB()
    {
        GenericList IorbQueue = (GenericList)iorbQueue;
        IORB iorb = getIORB();

        if(iorb!=null)
            return (IORB) IorbQueue.remove(iorb);
        else
            return null;//the queue is empty

    }

    /**
        Remove all IORBs that belong to the given ThreadCB from 
	this device's IORB queue

        The method is called when the thread dies and the I/O 
        operations it requested are no longer necessary. The memory 
        page used by the IORB must be unlocked and the IORB count for 
	the IORB's file must be decremented.

	@param thread thread whose I/O is being canceled

        @OSPProject Devices
    */
    public void do_cancelPendingIO(ThreadCB thread)
    {
        GenericList IorbQueue = (GenericList)iorbQueue;

        int i = 0;
        while(i<IorbQueue.length()){
            IORB iorb = (IORB) IorbQueue.getAt(i);

            if(iorb.getThread()==thread){
                iorb.getPage().unlock();//unlock pages
                iorb.getOpenFile().decrementIORBCount();//decrement openFile

                if(iorb.getOpenFile().getIORBCount() == 0 && iorb.getOpenFile().closePending){
                    iorb.getOpenFile().close();
                }
                IorbQueue.remove(iorb);//remove iorb
                clocks.remove(i);
            }
            else
                i++;
        }


    }

    /** Called by OSP after printing an error message. The student can
	insert code here to print various tables and data structures
	in their state just after the error happened.  The body can be
	left empty, if this feature is not used.
	
	@OSPProject Devices
     */
    public static void atError()
    {
        // your code goes here

    }

    /** Called by OSP after printing a warning message. The student
	can insert code here to print various tables and data
	structures in their state just after the warning happened.
	The body can be left empty, if this feature is not used.
	
	@OSPProject Devices
     */
    public static void atWarning()
    {
        // your code goes here
    }


    /*
       Feel free to add methods/fields to improve the readability of your code
    */

    private IORB getIORB()
    {
        GenericList IorbQueue = (GenericList)iorbQueue;
        Disk disk = (Disk)this;
        int currentCylinder = disk.getHeadPosition();

        for(int i=0;i<IorbQueue.length();i++){ //serve the cylinder the head already at
            IORB iorb = (IORB) IorbQueue.getAt(i);
            if(iorb.getCylinder() == currentCylinder)
                return iorb;
        }

        long higherCylindersAge = 0;
        long lowerCylinderAge = 0;
        long currentClock = HClock.get();

        for(int i=0;i<IorbQueue.length();i++){//find out which side's total age is higher
            IORB iorb = (IORB) IorbQueue.getAt(i);
            long enqueueClock = clocks.get(i);
            long age = currentClock - enqueueClock;

            if(iorb.getCylinder()<currentCylinder)
                lowerCylinderAge += age;

            if(iorb.getCylinder()>currentCylinder)
                higherCylindersAge += age;

        }

        int cylinderDifference = 0;
        int headPosition = 0;
        //find the nearest cylinder on that side
        for(int i=0;i<IorbQueue.length();i++){
            IORB iorb = (IORB) IorbQueue.getAt(i);
            int iorbCylinder = iorb.getCylinder();

            if(higherCylindersAge>=lowerCylinderAge){
                if(iorbCylinder>currentCylinder) {
                    int currentCylinderDifference = Math.abs(iorbCylinder - currentCylinder);
                    if (cylinderDifference==0 || currentCylinderDifference<cylinderDifference){
                        cylinderDifference = currentCylinderDifference;
                        headPosition = iorbCylinder;
                    }
                }
            }
            else{
                if(iorbCylinder<currentCylinder) {
                    int currentCylinderDifference = Math.abs(iorbCylinder - currentCylinder);
                    if (cylinderDifference==0 || currentCylinderDifference<cylinderDifference){
                        cylinderDifference = currentCylinderDifference;
                        headPosition = iorbCylinder;
                    }
                }
            }

        }

        disk.setHeadPosition(headPosition);//move assembly arm to that cylinder

        for(int i=0;i<IorbQueue.length();i++){//serve the new cylinder
            IORB iorb = (IORB) IorbQueue.getAt(i);
            if(iorb.getCylinder() == headPosition)
                return iorb;
        }

        return null;
    }

}

/*
      Feel free to add local classes to improve the readability of your code
*/
