/*
 * This file is part of the GeoLatte project.
 *
 *     GeoLatte is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     GeoLatte is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with GeoLatte.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2010 - 2011 and Ownership of code is shared by:
 * Qmino bvba - Esperantolaan 4 - 3001 Heverlee  (http://www.qmino.com)
 * Geovise bvba - Generaal Eisenhowerlei 9 - 2140 Antwerpen (http://www.geovise.com)
 */

package org.geolatte.featureserver.dbase;

import java.text.ParseException;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.geolatte.common.cql.hibernate.CqlHibernate;
import org.geolatte.common.reflection.EntityClassReader;
import org.geolatte.common.transformer.TransformerSource;
import org.geolatte.geom.Envelope;
import org.geolatte.geom.jts.JTS;
import org.hibernatespatial.criterion.SpatialRestrictions;

import org.hibernate.Criteria;
import org.hibernate.HibernateException;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Projections;

/**
 * This class is responsible for the actual retrieval of all objects
 * that match a certain entityclass from the database. It implements the TransformerSource api so it can
 * be used in transformationchains.
 * <p/>
 * <p>
 * <i>Creation-Date</i>: 9-apr-2010<br>
 * <i>Creation-Time</i>:  11:48:54<br>
 * </p>
 *
 * @author Yves Vandewoude
 * @author <a href="http://www.qmino.com">Qmino bvba</a>
 * @since SDK1.5
 */
public class StandardFeatureReader extends TransformerSource<Object> {

    private SessionFactory sessionFactory;
    private ScrollableResults results = null;
    private Class entityClass = null;
    private Transaction trans = null;
    private ReaderIterator readerIterator = new ReaderIterator();
    private static final Logger LOGGER = LogManager.getLogger(StandardFeatureReader.class);

    // The total number of elements matching the query, disregarding pagination
    private int totalCount;

    /**
     * Base constructor of a reader that will read all objects in the table. If a CQL expression is provided, only those
     * entities that match the cqlstring are returned. IF the cql expression is not provided, all elements of the entityclass
     * are returned. If the boundingbox is specified, it is also applied on the result: the entityClass, CQL and bbox
     * are therefore AND: all given criteria must be valid in order for a feature to be read by this reader. Only the
     * entityClass is required.
     *
     * @param factory     The sessionfactory to use.
     * @param cqlString   The detached criterium to apply to the query. Can be null.
     * @param entityClass The entityclass of the objects to retrieve. Required.
     * @param bbox  	  A boundingbox filter. Used on the geometry-property of the given entity. The geometry
     *                    field is determined by considering the entityClass as a feature (@see org.geolatte.core.reflection.EntityClassReader).
     *                    The bbox filter is ignored if it is invalid, null or if the entityClass does not contain
     *                    a geometryfield
     * @param limit       If specified (may be null), this parameter denotes the maximum number of elements that will be returned
     *                    by this. Used for pagination.
     * @param start       If specified (may be null); this parameter denotes the number of the first element to be returned. Used for pagination.
     * @param orderings   A list of orderings on columns. If null or empty, parameter is ignored. Otherwise, the orderings are applied
     *                    on the query result in the order of the list (so the results are sorted first according to the first order and so on).
     * @throws DatabaseException if a problem occurs that would prevent retrieval of items (eg: if the cql string is invalid)
     */
    public StandardFeatureReader(SessionFactory factory, String cqlString, Class entityClass, Envelope bbox,
                                 Integer start, Integer limit, List<Order> orderings)
            throws DatabaseException {
        this.sessionFactory = factory;
        this.entityClass = entityClass;
        DetachedCriteria detCrit = cqlToCriteria(cqlString, entityClass);
        try {
            beginTransaction(factory);
            Criteria criteria = toExecutableCriteria(factory, entityClass, detCrit);
            addBBoxConstraint(entityClass, bbox, criteria);
            getResultCount(criteria);
            resetToScroll(criteria);
            setStart(start, criteria);
            setLimit(limit, criteria);
            setOrderings(orderings, criteria);
            scroll(criteria);
        } catch (HibernateException he) {
            rollBackTransaction();
            closeSession();
            throw new DatabaseException(he);
        }
    }

    /**
     * Base constructor of a reader that will read all objects in the table corresponding with the
     * given entityClass
     *
     * @param factory     The sessionfactory to use.
     * @param entityClass The entityclass of the objects to retrieve
     * @param bbox  A boundingbox filter. Used on the geometry-property of the given entity. The geometry
     *                    field is determined by considering the entityClass as a feature (@see org.geolatte.core.reflection.EntityClassReader).
     *                    The bbox filter is ignored if it is invalid, null or if the entityClass does not contain
     *                    a geometryfield
     * @throws DatabaseException If an error would occur that would prevent retrieval of items from the database
     */
    public StandardFeatureReader(SessionFactory factory, Class entityClass, Envelope bbox) throws DatabaseException {
        this(factory, null, entityClass, bbox, null, null, null);
    }

    private void setOrderings(List<Order> orderings, Criteria criteria) {
        if (orderings != null) {
            for (Order o : orderings) {
                criteria.addOrder(o);
            }
        }
    }

    private void beginTransaction(SessionFactory factory) {
        trans = factory.getCurrentSession().beginTransaction();
    }

    private Criteria toExecutableCriteria(SessionFactory factory, Class entityClass, DetachedCriteria detCrit) {
        if(detCrit == null){
            return factory.getCurrentSession().createCriteria(entityClass);
        } else {
            return detCrit.getExecutableCriteria(factory.getCurrentSession());
        }
    }

    private DetachedCriteria cqlToCriteria(String cqlString, Class entityClass) {
        if (cqlString == null) return null;
        try {
            return CqlHibernate.toCriteria(cqlString, entityClass);
        } catch (ParseException e) {
            throw new DatabaseException(e);
        }
    }

    private void resetToScroll(Criteria crit) {
        crit.setProjection(null);
        crit.setResultTransformer(Criteria.ROOT_ENTITY);
    }

    private void addBBoxConstraint(Class entityClass, Envelope bbox, Criteria crit) {
        if (bbox != null) {
            String geomName = EntityClassReader.getClassReaderFor(entityClass).getGeometryName();
            if (geomName != null) {
                crit.add(SpatialRestrictions.filter(geomName, JTS.to(bbox), bbox.getCrsId().getCode()));
            }
        }
    }

    private void getResultCount(Criteria crit) {
        crit.setProjection(Projections.rowCount());
        Number count = (Number) crit.uniqueResult();
        totalCount = count.intValue();
    }

    private void setStart(Integer start, Criteria crit) {
        if (start != null) {
            crit.setFirstResult(start);
        }
    }

    private void setLimit(Integer limit, Criteria crit) {
        if (limit != null) {
            crit.setMaxResults(limit);
        }
    }

    private void scroll(Criteria crit) {
        crit.setFetchSize(1024);
        results = crit.scroll(ScrollMode.FORWARD_ONLY);
    }



    /**
     * @return The total number of elements, disregarding pagination parameters
     */
    public int getTotalCount() {
        return totalCount;
    }

    /**
     * Implementation of the TransformerSource api
     *
     * @return An iterable over the output objects. Individual objects from the database can be retrieved
     *         with this interface
     */
    @Override
    protected Iterable<Object> output() {
        return new Iterable<Object>() {
            public Iterator<Object> iterator() {
                return readerIterator;
            }
        };
    }

    /**
     * Closes this feature reader, releasing its resources
     */
    public void close() {
        rollBackTransaction();
        closeSession();
    }

    private void closeSession() {
        try {
            sessionFactory.getCurrentSession().close();
        } catch (HibernateException e) {
            LOGGER.error("Exception thrown while closing the session", e);
        }
    }

    private void rollBackTransaction() {
        try {
            if (trans != null) {
                trans.rollback();
            }
        }
        catch (HibernateException e) {
            LOGGER.error("Exception thrown while rolling back transanction", e);
        }
    }

    /**
     * @return the entityClass corresponding with the featureserver table for this reader.
     */
    public Class getEntityClass() {
        return entityClass;
    }

    private class ReaderIterator implements Iterator<Object> {

        Object nextCached = null;

        /**
         * Returns <tt>true</tt> if the iteration has more elements. (In other
         * words, returns <tt>true</tt> if <tt>next</tt> would return an element
         * rather than throwing an exception.)
         *
         * @return <tt>true</tt> if the iterator has more elements.
         */
        public boolean hasNext() {
            if (results == null) {
                return false;
            }

            if (nextCached == null) {
                results.next();
                Object[] currentRow = results.get();
                if (currentRow != null) {
                    nextCached = currentRow[0];
                }
            }
            if (nextCached == null) {
                return false;
            }
            return true;
        }

        /**
         * Returns the next element in the iteration.
         *
         * @return the next element in the iteration.
         * @throws java.util.NoSuchElementException
         *          iteration has no more elements.
         */
        public Object next() {
            if (hasNext()) {
                Object result = nextCached;
                nextCached = null;
                return result;
            } else {
                throw new NoSuchElementException("No more elements in iterable");
            }
        }

        /**
         * Not supported;
         */
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}
