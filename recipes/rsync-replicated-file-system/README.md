Near real time rsync replicated file system
===========================================

Quickdemo
=========

* This demo starts 3 instances with id's as ```localhost_12001, localhost_12002, localhost_12003```
* Each instance stores its files under /tmp/<id>/filestore
* ``` localhost_12001 ``` is designated as the master and ``` localhost_12002 and localhost_12003``` are the slaves.
* Files written to master are replicated to the slaves automatically. In this demo, a.txt and b.txt are written to ```/tmp/localhost_12001/filestore``` and it gets replicated to other folders.
* When the master is stopped, ```localhost_12002``` is promoted to master. 
* The other slave ```localhost_12003``` stops replicating from ```localhost_12001``` and starts replicating from new master ```localhost_12002```
* Files written to new master ```localhost_12002``` are replicated to ```localhost_12003```
* In the end state of this quick demo, ```localhost_12002``` is the master and ```localhost_12003``` is the slave. Manually create files under ```/tmp/localhost_12002/filestore``` and see that appears in ```/tmp/localhost_12003/filestore```

```
git clone https://git-wip-us.apache.org/repos/asf/incubator-helix.git
cd recipes/rsync-replicated-file-system/
mvn clean install package
cd target/rsync-replicated-file-system-pkg/bin
./quickdemo

```


Overview
========

There are many applications that require storage for storing large number of relatively small data files. Examples include media stores to store small videos, images, mail attachments etc. Each of these objects is typically kilobytes, often no larger than a few megabytes. An additional distinguishing feature of these usecases is also that files are typically only added or deleted, rarely updated. When there are updates, they are rare and do not have any concurrency requirements.

These are much simpler requirements than what general purpose distributed file system have to satisfy including concurrent access to files, random access for reads and updates, posix compliance etc. To satisfy those requirements, general DFSs are also pretty complex that are expensive to build and maintain.
 
A different implementation of a distributed file system includes HDFS which is inspired by Google�s GFS. This is one of the most widely used distributed file system that forms the main data storage platform for Hadoop. HDFS is primary aimed at processing very large data sets and distributes files across a cluster of commodity servers by splitting up files in fixed size chunks. HDFS is not particularly well suited for storing a very large number of relatively tiny files.

### File Store

It�s possible to build a vastly simpler system for the class of applications that have simpler requirements as we have pointed out.

* Large number of files but each file is relatively small.
* Access is limited to create, delete and get entire files.
* No updates to files that are already created (or it�s feasible to delete the old file and create a new one).
 

We call this system a Partitioned File Store (PFS) to distinguish it from other distributed file systems. This system needs to provide the following features:

* CRD access to large number of small files
* Scalability: Files should be distributed across a large number of commodity servers based on the storage requirement.
* Fault-tolerance: Each file should be replicated on multiple servers so that individual server failures do not reduce availability.
* Elasticity: It should be possible to add capacity to the cluster easily.
 

Apache Helix is a generic cluster management framework that makes it very easy to provide the scalability, fault-tolerance and elasticity features. 
Rsync can be easily used as a replication channel between servers so that each file gets replicated on multiple servers.

Design
======

High level 

* Partition the file system based on the file name. 
* At any time a single writer can write, we call this a master.
* For redundancy, we need to have additional replicas called slave. Slaves can optionally serve reads.
* Slave replicates data from the master.
* When a master fails, slave gets promoted to master.

### Transaction log

Every write on the master will result in creation/deletion of one or more files. In order to maintain timeline consistency slaves need to apply the changes in the same order. 
To facilitate this, the master logs each transaction in a file. Each transaction is associated with an id. The transaction id has two parts a generation and sequence. 
For every transaction the sequence number increases and and generation increases when a new master is elected. 

### Replication

Replication is needed for the slave to keep up with the changes on the master. Every time the slave applies a change it checkpoints the last applied transaction id. 
During restarts, this allows the slave the restart to pull changes from the last checkpointed transaction. Similar to a master, the slave logs each transaction to the transaction logs but 
instead of generating new transaction id, it uses the same id generated by the master.


### Fail over

When a master fails, a new slave will be promoted to master. If the prev master node is reachable, then the new master will flush all the 
changes from previous master before taking up mastership. The new master will record the end transaction id of the current generation and then starts new generation 
with sequence starting from 1. After this the master will begin accepting writes. 


![Partitioned File Store](images/system.png)















