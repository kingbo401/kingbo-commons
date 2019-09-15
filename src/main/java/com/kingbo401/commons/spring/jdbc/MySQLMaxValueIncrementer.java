package com.kingbo401.commons.spring.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.incrementer.AbstractDataFieldMaxValueIncrementer;

/**
 * 改造spring自带MySQLMaxValueIncrementer，使单表支持多个sequence
 * 表结构：sequence.sql
 * @author kingbo
 *
 */
public class MySQLMaxValueIncrementer extends AbstractDataFieldMaxValueIncrementer {

	/** The SQL string for retrieving the new sequence value */
	private static final String VALUE_SQL = "select last_insert_id()";

	/** The next id to serve */
	private long nextId = 0;

	/** The max id to serve */
	private long maxId = 0;

	/** Whether or not to use a new connection for the incrementer */
	private boolean useNewConnection = false;

	/**
	 * sequence name
	 */
	private String sequence;
	
	/** The number of keys buffered in a cache */
	private int cacheSize = 1;

	/**
	 * Default constructor for bean property style usage.
	 * @see #setDataSource
	 * @see #setIncrementerName
	 * @see #setColumnName
	 */
	public MySQLMaxValueIncrementer() {
	}

	/**
	 * Convenience constructor.
	 * @param dataSource the DataSource to use
	 * @param incrementerName the name of the sequence table to use
	 * @param columnName the name of the column in the sequence table to use
	 */
	public MySQLMaxValueIncrementer(DataSource dataSource, String incrementerName, String columnName) {
		super(dataSource, incrementerName);
	}


	/**
	 * Set whether to use a new connection for the incrementer.
	 * <p>{@code true} is necessary to support transactional storage engines,
	 * using an isolated separate transaction for the increment operation.
	 * {@code false} is sufficient if the storage engine of the sequence table
	 * is non-transactional (like MYISAM), avoiding the effort of acquiring an
	 * extra {@code Connection} for the increment operation.
	 * <p>Default is {@code false} in the Spring Framework 4.3.x line.
	 * @since 4.3.6
	 * @see DataSource#getConnection()
	 */
	public void setUseNewConnection(boolean useNewConnection) {
		this.useNewConnection = useNewConnection;
	}


	@Override
	protected synchronized long getNextKey() throws DataAccessException {
		if (this.maxId == this.nextId) {
			/*
			* If useNewConnection is true, then we obtain a non-managed connection so our modifications
			* are handled in a separate transaction. If it is false, then we use the current transaction's
			* connection relying on the use of a non-transactional storage engine like MYISAM for the
			* incrementer table. We also use straight JDBC code because we need to make sure that the insert
			* and select are performed on the same connection (otherwise we can't be sure that last_insert_id()
			* returned the correct value).
			*/
			Connection con = null;
			Statement stmt = null;
			boolean mustRestoreAutoCommit = false;
			try {
				if (this.useNewConnection) {
					con = getDataSource().getConnection();
					if (con.getAutoCommit()) {
						mustRestoreAutoCommit = true;
						con.setAutoCommit(false);
					}
				}
				else {
					con = DataSourceUtils.getConnection(getDataSource());
				}
				stmt = con.createStatement();
				if (!this.useNewConnection) {
					DataSourceUtils.applyTransactionTimeout(stmt, getDataSource());
				}
				// Increment the sequence column...
				try {
					stmt.executeUpdate("update " + getIncrementerName() + " set value = "  +
							"last_insert_id(value + (idx + 1)*" + this.cacheSize + ")"
							+ " where name='" + this.sequence + "'");
				}
				catch (SQLException ex) {
					throw new DataAccessResourceFailureException("Could not increment " + this.sequence + " for " +
							getIncrementerName() + " sequence table", ex);
				}
				// Retrieve the new max of the sequence column...
				ResultSet rs = stmt.executeQuery(VALUE_SQL);
				try {
					if (!rs.next()) {
						throw new DataAccessResourceFailureException("last_insert_id() failed after executing an update");
					}
					this.maxId = rs.getLong(1);
				}
				finally {
					JdbcUtils.closeResultSet(rs);
				}
				this.nextId = this.maxId - this.cacheSize + 1;
			}
			catch (SQLException ex) {
				throw new DataAccessResourceFailureException("Could not obtain last_insert_id()", ex);
			}
			finally {
				JdbcUtils.closeStatement(stmt);
				if (con != null) {
					if (this.useNewConnection) {
						try {
							con.commit();
							if (mustRestoreAutoCommit) {
								con.setAutoCommit(true);
							}
						}
						catch (SQLException ignore) {
							throw new DataAccessResourceFailureException(
									"Unable to commit new sequence value changes for " + getIncrementerName());
						}
						JdbcUtils.closeConnection(con);
					}
					else {
						DataSourceUtils.releaseConnection(con, getDataSource());
					}
				}
			}
		}
		else {
			this.nextId++;
		}
		return this.nextId;
	}
}
