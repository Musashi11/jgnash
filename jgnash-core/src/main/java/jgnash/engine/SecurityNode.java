/*
 * jGnash, a personal finance application
 * Copyright (C) 2001-2013 Craig Cavanaugh
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package jgnash.engine;

import jgnash.util.DateUtils;

import java.io.ObjectStreamException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.persistence.CascadeType;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.JoinTable;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.PostLoad;

/**
 * Security Node
 *
 * @author Craig Cavanaugh
 */
@Entity
public class SecurityNode extends CommodityNode {

    private static final long serialVersionUID = -8377663762619941498L;

    @ManyToOne
    private CurrencyNode reportedCurrency;

    /**
     * The currency that security values are reported in
     */
    @Enumerated(EnumType.STRING)
    private QuoteSource quoteSource = QuoteSource.NONE;

    /**
     * ISIN or CUSIP.  Used for OFX and quote downloads
     */
    private String isin;

    @JoinTable
    @OneToMany(cascade = {CascadeType.ALL})
    private Set<SecurityHistoryNode> historyNodes = new HashSet<>();

    private transient ReadWriteLock lock;

    public SecurityNode() {
        lock = new ReentrantReadWriteLock(true);
    }

    public SecurityNode(final CurrencyNode node) {
        this();
        setReportedCurrencyNode(node);
    }

    /**
     * Prefix is deferred to the reported currency
     *
     * @return prefix of the reported currency
     */
    @Override
    public String getPrefix() {
        return reportedCurrency.getPrefix();
    }

    @Override
    public void setPrefix(final String ignored) {
    }

    /**
     * Suffix is deferred to the reported currency
     *
     * @return suffix of the reported currency
     */
    @Override
    public String getSuffix() {
        return reportedCurrency.getSuffix();
    }

    @Override
    public void setSuffix(final String ignored) {
    }

    /**
     * Returns the quote download source
     *
     * @return quote download source
     */
    public QuoteSource getQuoteSource() {
        return quoteSource;
    }

    /**
     * Sets the quote download source
     *
     * @param source QuoteSource to use
     */
    public void setQuoteSource(final QuoteSource source) {
        quoteSource = source;
    }

    public String getISIN() {
        return isin;
    }

    public void setISIN(final String isin) {
        this.isin = isin;
    }

    /**
     * Set the CurrencyNode that security histories are reported in
     *
     * @param node reported CurrencyNode
     */
    public void setReportedCurrencyNode(final CurrencyNode node) {
        reportedCurrency = node;
    }

    /**
     * Returns the CurrencyNode that security histories are reported in
     *
     * @return reported CurrencyNode
     */
    public CurrencyNode getReportedCurrencyNode() {
        return reportedCurrency;
    }

    boolean addHistoryNode(final SecurityHistoryNode node) {

        lock.writeLock().lock();

        try {
            return historyNodes.add(node);
        } finally {
            lock.writeLock().unlock();
        }
    }

    boolean removeHistoryNode(final SecurityHistoryNode hNode) {

        lock.writeLock().lock();

        try {
            return historyNodes.remove(hNode);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns <tt>true</tt> if this SecurityNode contains the specified element.
     *
     * @param historyNode SecurityHistoryNode whose presence in this SecurityNode is to be tested
     * @return <tt>true</tt> if this SecurityNode contains the specified SecurityHistoryNode
     */
    public boolean contains(final SecurityHistoryNode historyNode) {
        lock.readLock().lock();

        try {
            return historyNodes.contains(historyNode);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     *
     * @return A sorted list of the security history
     */
    private List<SecurityHistoryNode> getSortedList() {
        lock.readLock().lock();

        try {
            ArrayList<SecurityHistoryNode> sorted = new ArrayList<>(historyNodes);
            Collections.sort(sorted);
            return sorted;
        } finally {
            lock.readLock().unlock();
        }
    }

    private SecurityHistoryNode getLastHistoryNode() {
        lock.readLock().lock();

        try {
            SecurityHistoryNode node = null;

            if (!historyNodes.isEmpty()) {
                List<SecurityHistoryNode> sorted = getSortedList();

                node = sorted.get(sorted.size() - 1);
            }

            return node;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Get a copy of SecurityHistoryNodes for this security
     *
     * @return Returns a shallow copy of the history nodes to protect against
     *         modification
     */
    public List<SecurityHistoryNode> getHistoryNodes() {
        return getSortedList();
    }

    SecurityHistoryNode getHistoryNode(final Date date) {
        Date testDate = DateUtils.trimDate(date);

        lock.readLock().lock();

        List<SecurityHistoryNode> sortedList = getSortedList();

        try {

            SecurityHistoryNode hNode = null;

            for (int i = sortedList.size() - 1; i >= 0; i--) {
                SecurityHistoryNode node = sortedList.get(i);

                if (testDate.compareTo(node.getDate()) >= 0) {
                    hNode = node;
                    break;
                }
            }

            if (hNode == null) {
                hNode = getLastHistoryNode();
            }

            return hNode;
        } finally {
            lock.readLock().unlock();
        }
    }

    BigDecimal getMarketPrice(final Date date) {
        BigDecimal marketPrice = BigDecimal.ZERO;

        Date testDate = DateUtils.trimDate(date);

        lock.readLock().lock();

        try {

            for (SecurityHistoryNode node : historyNodes) {
                if (node.getDate().getTime() <= testDate.getTime()) {
                    marketPrice = node.getPrice();
                } else {
                    break;
                }
            }

            return marketPrice;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the latest market price exchanged to the specified currency
     *
     * @param date date to find closest matching rate without exceeding
     * @param node currency to exchange to
     * @return latest market price
     */
    public BigDecimal getMarketPrice(final Date date, final CurrencyNode node) {
        return getMarketPrice(date).multiply(getReportedCurrencyNode().getExchangeRate(node));
    }

    /**
     * Return a clone of this security node Security history is not cloned
     *
     * @return clone of this SecurityNode with history nodes
     */
    @Override
    public Object clone() throws CloneNotSupportedException {
        SecurityNode node = (SecurityNode) super.clone();
        node.historyNodes = new HashSet<>();
        node.lock = new ReentrantReadWriteLock(true);

        return node;
    }

    private Object readResolve() throws ObjectStreamException {
        lock = new ReentrantReadWriteLock(true);
        return this;
    }

    @PostLoad
    @SuppressWarnings("unused")
    private void postLoad() {
        lock = new ReentrantReadWriteLock(true);
    }
}
