package osp.Devices;
import java.util.*;
import osp.Devices.Device;
import osp.Devices.IORB;
import osp.IFLModules.*;
import osp.Hardware.*;
import osp.Interrupts.*;
import osp.Threads.*;
import osp.Utilities.*;
import osp.Tasks.*;
import osp.Memory.*;
import osp.FileSys.*;

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
    The disk interrupt handler.  When a disk I/O interrupt occurs,
    this class is called upon the handle the interrupt.

    @OSPProject Devices
*/
public class DiskInterruptHandler extends IflDiskInterruptHandler
{
    /** 
        Handles disk interrupts. 
        
        This method obtains the interrupt parameters from the 
        interrupt vector. The parameters are IORB that caused the 
        interrupt: (IORB)InterruptVector.getEvent(), 
        and thread that initiated the I/O operation: 
        InterruptVector.getThread().
        The IORB object contains references to the memory page 
        and open file object that participated in the I/O.
        
        The method must unlock the page, set its IORB field to null,
        and decrement the file's IORB count.
        
        The method must set the frame as dirty if it was memory write 
        (but not, if it was a swap-in, check whether the device was 
        SwapDevice)

        As the last thing, all threads that were waiting for this 
        event to finish, must be resumed.

        @OSPProject Devices 
    */
    public void do_handleInterrupt()
    {
        //obtain information
        IORB iorb = (IORB) InterruptVector.getEvent();
        OpenFile openFile = iorb.getOpenFile();
        PageTableEntry page = iorb.getPage();
        ThreadCB thread = iorb.getThread();
        int deviceID = iorb.getDeviceID();
        int ioType = iorb.getIOType();

        //decrement openFile
        openFile.decrementIORBCount();

        //close if needed
        if(openFile.getIORBCount()==0 && openFile.closePending)
            openFile.close();

        //unlock page
        page.unlock();

        //
        if(thread.getTask().getStatus()==TaskLive ){
            if(thread.getStatus()!=ThreadKill){
                if(deviceID!=SwapDeviceID){
                    page.getFrame().setReferenced(true);
                    if(ioType==FileRead)
                        page.getFrame().setDirty(true);
                }
                else//swap-in or swap-out
                    page.getFrame().setDirty(false);
            }

        }
        else{//unreserve if the task is dead
            if(page.getFrame().getReserved()==thread.getTask())
                page.getFrame().setUnreserved(thread.getTask());
        }

        //wake up threads
        iorb.notifyThreads();

        //set the device to idle
        Device.get(deviceID).setBusy(false);

        //serve next iorb
        IORB next = Device.get(deviceID).dequeueIORB();
        if(next!=null)
            Device.get(deviceID).startIO(next);

        //dispatch
        ThreadCB.dispatch();

    }


    /*
       Feel free to add methods/fields to improve the readability of your code
    */

}

/*
      Feel free to add local classes to improve the readability of your code
*/
