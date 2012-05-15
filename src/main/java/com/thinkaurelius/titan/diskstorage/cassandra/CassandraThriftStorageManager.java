package com.thinkaurelius.titan.diskstorage.cassandra;

import static com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration.STORAGE_DIRECTORY_KEY;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.cassandra.thrift.Cassandra;
import org.apache.cassandra.thrift.CfDef;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.thrift.KsDef;
import org.apache.cassandra.thrift.NotFoundException;
import org.apache.cassandra.thrift.SchemaDisagreementException;
import org.apache.commons.configuration.Configuration;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.thinkaurelius.titan.diskstorage.StorageManager;
import com.thinkaurelius.titan.diskstorage.TransactionHandle;
import com.thinkaurelius.titan.diskstorage.cassandra.thriftpool.CTConnection;
import com.thinkaurelius.titan.diskstorage.cassandra.thriftpool.CTConnectionFactory;
import com.thinkaurelius.titan.diskstorage.cassandra.thriftpool.CTConnectionPool;
import com.thinkaurelius.titan.diskstorage.cassandra.thriftpool.UncheckedGenericKeyedObjectPool;
import com.thinkaurelius.titan.diskstorage.util.LocalIDManager;
import com.thinkaurelius.titan.exceptions.GraphStorageException;

public class CassandraThriftStorageManager implements StorageManager {

    private static final Logger logger =
            LoggerFactory.getLogger(CassandraThriftStorageManager.class);
    
    public static final String PROP_KEYSPACE = "keyspace";
    public static final String PROP_HOSTNAME = "hostname";
    public static final String PROP_PORT = "port";
    public static final String PROP_SELF_HOSTNAME = "selfHostname";
    public static final String PROP_TIMEOUT = "thrift_timeout";

    public static Map<String, CassandraThriftOrderedKeyColumnValueStore> stores =
    		new ConcurrentHashMap<String, CassandraThriftOrderedKeyColumnValueStore>();
    
    /**
     * Default name for the Cassandra keyspace
     * <p>
     * Value = {@value}
     */
    public static final String DEFAULT_KEYSPACE = "titantest00";

    /**
     * Default hostname at which to attempt Cassandra Thrift connection.
     * <p>
     * Value = {@value}
     */
    public static final String DEFAULT_HOSTNAME = null;

    /**
     * Default canonical hostname of the local machine.
     * <p>
     * Value = {@value}
     */
    public static final String DEFAULT_SELF_HOSTNAME = null;

    /**
     * Default timeout for Thrift TSocket objects used to
     * connect to the Cassandra cluster.
     * <p>
     * Value = {@value}
     */
    public static final int DEFAULT_THRIFT_TIMEOUT_MS = 10000;

    /**
     * Default port at which to attempt Cassandra Thrift connection.
     * <p>
     * Value = {@value}
     */
    public static final int DEFAULT_PORT = 9160;
    
    /**
     * Default column family used for ID block management.
     * <p>
     * Value = {@value}
     */
    public static final String idCfName = "id_allocations";

	private final String keyspace;
	
	private final UncheckedGenericKeyedObjectPool
			<String, CTConnection> pool;

    private final LocalIDManager idmanager;
	
	public CassandraThriftStorageManager(Configuration config) {
		this.keyspace = config.getString(PROP_KEYSPACE,DEFAULT_KEYSPACE);
		
		this.pool = CTConnectionPool.getPool(
				interpretHostname(config.getString(PROP_HOSTNAME,DEFAULT_HOSTNAME)),
				config.getInt(PROP_PORT,DEFAULT_PORT),
				config.getInt(PROP_TIMEOUT,DEFAULT_THRIFT_TIMEOUT_MS));
        idmanager = new LocalIDManager(config.getString(STORAGE_DIRECTORY_KEY) + File.separator + LocalIDManager.DEFAULT_NAME);
	}

    @Override
    public long[] getIDBlock(int partition, int blockSize) {
        return idmanager.getIDBlock(partition,blockSize);
    }


	@Override
	public TransactionHandle beginTransaction() {
		return new CassandraTransaction(this);
	}

	@Override
	public void close() {
        //Do nothing
	}

	@Override
	public CassandraThriftOrderedKeyColumnValueStore openDatabase(final String name)
			throws GraphStorageException {
	
                synchronized (stores) {

		final String lockCfName = getLockColumnFamilyName(name);

	
		CassandraThriftOrderedKeyColumnValueStore store =
				stores.get(name);
		
		if (null != store) {
			return store;
		}

		CTConnection conn = null;
		try {
			conn =  pool.genericBorrowObject(keyspace);
			Cassandra.Client client = conn.getClient();
			logger.debug("Looking up metadata on keyspace {}...", keyspace);
			KsDef keyspaceDef = client.describe_keyspace(keyspace);
			boolean foundColumnFamily = false;
			boolean foundLockColumnFamily = false;
			for (CfDef cfDef : keyspaceDef.getCf_defs()) {
				String curCfName = cfDef.getName();
				if (curCfName.equals(name)) {
					foundColumnFamily = true;
				} else if (curCfName.equals(lockCfName)) {
					foundLockColumnFamily = true;
				}
			}
			if (!foundColumnFamily) {
				createColumnFamily(client, name);
			}
			if (!foundLockColumnFamily) {
				createColumnFamily(client, lockCfName);
			}
		} catch (TException e) {
			throw new GraphStorageException(e);
		} catch (InvalidRequestException e) {
			throw new GraphStorageException(e);
		} catch (NotFoundException e) {
			throw new GraphStorageException(e);
		} finally {
			if (null != conn)
				pool.genericReturnObject(keyspace, conn);
		}
		
		store = new CassandraThriftOrderedKeyColumnValueStore(keyspace, name, pool);
                CassandraThriftOrderedKeyColumnValueStore lockStore =
                        new CassandraThriftOrderedKeyColumnValueStore(keyspace, lockCfName, pool);

		stores.put(name, store);
                stores.put(lockCfName, lockStore);
		
		return store;
                }
	}
	
	CassandraThriftOrderedKeyColumnValueStore getOpenedDatabase(String name) {
		return stores.get(name);
	}
	
	String getLockColumnFamilyName(String cfName) {
		return cfName + "_locks";
	}
	
	/**
	 * Drop the named keyspace if it exists.  Otherwise, do nothing.
	 * 
	 * @throws GraphStorageException wrapping any unexpected Exception or
	 *         subclass of Exception
	 * @returns true if the keyspace was dropped, false if it was not present
	 */
	public boolean dropKeyspace(String keyspace) throws GraphStorageException {
		CTConnection conn = null;
		try {
			conn =  pool.genericBorrowObject(keyspace);
			Cassandra.Client client = conn.getClient();
			
			try {
				client.describe_keyspace(keyspace);
				// Keyspace must exist
				logger.debug("Dropping keyspace {}...", keyspace);
				String schemaVer = client.system_drop_keyspace(keyspace);
				
				// Try to let Cassandra converge on the new column family
				CTConnectionFactory.validateSchemaIsSettled(client, schemaVer);
			} catch (NotFoundException e) {
				// Keyspace doesn't exist yet: return immediately
				logger.debug("Keyspace {} does not exist, not attempting to drop", 
						keyspace);
				return false;
			}

                        stores.clear();
			return true;
		} catch (Exception e) {
			throw new GraphStorageException(e);
		} finally {
			if (null != conn)
				pool.genericReturnObject(keyspace, conn);
		}

	}
	
	
	/**
	 * Connect to Cassandra via Thrift on the specified host and
	 * port and attempt to drop the named keyspace.
	 * 
	 * This is a utility method intended mainly for testing.  It is
	 * equivalent to issuing "drop keyspace {@code <keyspace>};" in
	 * the cassandra-cli tool.
	 * 
	 * @param keyspace the keyspace to drop
	 * @throws RuntimeException if any checked Thrift or UnknownHostException
	 *         is thrown in the body of this method
	 */
	public static void dropKeyspace(String keyspace, String hostname, int port)
		throws GraphStorageException {
		CTConnection conn = null;
		try {
			conn = CTConnectionPool.getFactory(hostname, port, DEFAULT_THRIFT_TIMEOUT_MS).makeRawConnection();

			Cassandra.Client client = conn.getClient();
			
			try {
				client.describe_keyspace(keyspace);
				// Keyspace must exist
				logger.debug("Dropping keyspace {}...", keyspace);
				String schemaVer = client.system_drop_keyspace(keyspace);
				
				// Try to let Cassandra converge on the new column family
				CTConnectionFactory.validateSchemaIsSettled(client, schemaVer);

                                stores.clear();
			} catch (NotFoundException e) {
				// Keyspace doesn't exist yet: return immediately
				logger.debug("Keyspace {} does not exist, not attempting to drop", 
						keyspace);
			}
		} catch (Exception e) {
			throw new GraphStorageException(e);
		} finally {
			if (null != conn && conn.getTransport().isOpen())
				conn.getTransport().close();
		}
                
	}

    /**
     * If hostname is non-null, returns hostname.
     *
     * If hostname is null, returns the result of calling
     * InetAddress.getLocalHost().getCanonicalHostName().
     * Any exceptions generated during said call are rethrown as
     * RuntimeException.
     *
     * @throws RuntimeException in case of UnknownHostException for localhost
     * @return sanitized hostname
     */
    private static String interpretHostname(String hostname) {
        if (null == hostname) {
            try {
                return InetAddress.getLocalHost().getCanonicalHostName();
            } catch (UnknownHostException e) {
                throw new RuntimeException(e);
            }
        } else {
            return hostname;
        }
    }
    
    private void createColumnFamily(Cassandra.Client client, String cfName)
    		throws InvalidRequestException, TException {
		CfDef createColumnFamily = new CfDef();
		createColumnFamily.setName(cfName);
		createColumnFamily.setKeyspace(keyspace);
		createColumnFamily.setComparator_type("org.apache.cassandra.db.marshal.BytesType");
		logger.debug("Adding column family {} to keyspace {}...", cfName, keyspace);
        String schemaVer = null;
        try {
            schemaVer = client.system_add_column_family(createColumnFamily);
        } catch (SchemaDisagreementException e) {
            throw new GraphStorageException("Error in setting up column family",e);
        }
        logger.debug("Added column family {} to keyspace {}.", cfName, keyspace);
		
		// Try to let Cassandra converge on the new column family
		try {
			CTConnectionFactory.validateSchemaIsSettled(client, schemaVer);
		} catch (InterruptedException e) {
			throw new GraphStorageException(e);
		}
    	
    }
}
