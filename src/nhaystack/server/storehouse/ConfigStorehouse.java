//
// Copyright (c) 2012, J2 Innovations
// Licensed under the Academic Free License version 3.0
//
// History:
//   04 Oct 2012  Mike Jarmy  Creation
//
package nhaystack.server.storehouse;

import javax.baja.control.*;
import javax.baja.control.ext.*;
import javax.baja.driver.*;
import javax.baja.driver.point.*;
import javax.baja.history.*;
import javax.baja.history.ext.*;
import javax.baja.naming.*;
import javax.baja.status.*;
import javax.baja.sys.*;
import javax.baja.units.*;
import javax.baja.util.*;

import haystack.*;
import nhaystack.*;
import nhaystack.server.*;
import nhaystack.site.*;

/**
  * ConfigStorehouse manages access to the BComponentSpace
  */
public class ConfigStorehouse extends Storehouse
{
    public ConfigStorehouse(NHServer server)
    {
        super(server);
    }

    /**
      * Create the haystack representation of a BComponent.
      *
      * The haystack representation is a combination of the 
      * autogenerated tags, and those tags specified
      * in the explicit haystack annotation (if any).
      *
      * This method never returns null.
      */
    public HDict createComponentTags(BComponent comp)
    {
        if (comp instanceof BHTagged)
        {
            return ((BHTagged) comp).generateTags();
        }
        else
        {
            HDictBuilder hdb = new HDictBuilder();

            // add existing tags
            BHDict btags = BHDict.findTagAnnotation(comp);
            HDict tags = (btags == null) ?  HDict.EMPTY : btags.getDict();
            hdb.add(tags);

            // add id
            hdb.add("id", NHRef.make(comp).getHRef());

            // add misc other tags
            String dis = comp.getDisplayName(null);
            if (dis != null) hdb.add("dis", dis);
            hdb.add("axType", comp.getType().toString());
            hdb.add("axSlotPath", comp.getSlotPath().toString());

            // points get special treatment
            if (comp instanceof BControlPoint)
            {
                BControlPoint point = (BControlPoint) comp;

                // ensure there is a point marker tag
                hdb.add("point");

                // check if this point has a history
                BHistoryConfig cfg = server.getHistoryStorehouse()
                    .lookupHistoryFromPoint(point);
                if (cfg != null)
                {
                    hdb.add("his");

                    if (service.getShowLinkedHistories())
                        hdb.add("axHistoryRef", NHRef.make(cfg).getHRef());

                    // tz
                    if (!tags.has("tz"))
                    {
                        HTimeZone tz = makeTimeZone(cfg.getTimeZone());
                        hdb.add("tz", tz.name);
                    }

                    // hisInterpolate 
                    if (!tags.has("hisInterpolate"))
                    {
                        BHistoryExt historyExt = service.lookupHistoryExt(point);
                        if (historyExt != null && (historyExt instanceof BCovHistoryExt))
                            hdb.add("hisInterpolate", "cov");
                    }
                }

                // point kind tags
                int pointKind = getControlPointKind(point);
                BFacets facets = (BFacets) point.get("facets");
                addPointKindTags(pointKind, facets, tags, hdb);

                // cur, writable
                hdb.add("cur");
                if (point.isWritablePoint())
                    hdb.add("writable");

                // curVal, curStatus
                switch(pointKind)
                {
                    case NUMERIC_KIND:
                        BNumericPoint np = (BNumericPoint) point;

                        HNum curVal = null;
                        if (tags.has("unit"))
                        {
                            HVal unit = tags.get("unit");
                            curVal = HNum.make(np.getNumeric(), unit.toString());
                        }
                        else
                        {
                            BUnit unit = findUnit(facets);
                            if (unit == null) 
                                curVal = HNum.make(np.getNumeric());
                            else
                                curVal = HNum.make(np.getNumeric(), unit.toString());
                        }
                        hdb.add("curVal", curVal);
                        hdb.add("curStatus", makeStatusString(point.getStatus()));

                        break;

                    case BOOLEAN_KIND:
                        BBooleanPoint bp = (BBooleanPoint) point;
                        hdb.add("curVal",    HBool.make(bp.getBoolean()));
                        hdb.add("curStatus", makeStatusString(point.getStatus()));
                        break;

                    case ENUM_KIND:
                        BEnumPoint ep = (BEnumPoint) point;
                        hdb.add("curVal",    HStr.make(ep.getEnum().toString()));
                        hdb.add("curStatus", makeStatusString(point.getStatus()));
                        break;

                    case STRING_KIND:
                        BStringPoint sp = (BStringPoint) point;
                        hdb.add("curVal",    HStr.make(sp.getOut().getValue().toString()));
                        hdb.add("curStatus", makeStatusString(point.getStatus()));
                        break;
                }

                // the point is explicitly tagged with an equipRef
                if (tags.has("equipRef"))
                {
                    BComponent equip = server.lookupComponent((HRef) tags.get("equipRef"));

                    // try to look up  siteRef too
                    HDict equipTags = BHDict.findTagAnnotation(equip).getDict();
                    if (equipTags.has("siteRef"))
                        hdb.add("siteRef", equipTags.get("siteRef"));
                }
                // maybe we've cached an implicit equipRef
                else
                {
                    BComponent equip = server.getCache().getImplicitEquip(point);
                    if (equip != null)
                    {
                        hdb.add("equipRef", NHRef.make(equip).getHRef());

                        // try to look up  siteRef too
                        HDict equipTags = BHDict.findTagAnnotation(equip).getDict();
                        if (equipTags.has("siteRef"))
                            hdb.add("siteRef", equipTags.get("siteRef"));
                    }
                }
            }

            // done
            return hdb.toDict();
        }
    }

    /**
      * Return whether the given component
      * ought to be turned into a Haystack record.
      */
    public boolean isVisibleComponent(BComponent comp)
    {
        // Return true for components that have been 
        // annotated with a BHDict instance.
        if (BHDict.findTagAnnotation(comp) != null)
            return true;

        // Return true for BControlPoints.
        if (comp instanceof BControlPoint)
            return true;

        // nope
        return false;
    }

    /**
      * Return navigation tree children for given navId. 
      */
    public HGrid onNav(String navId)
    {
        // child of ComponentSpace root
        if (navId.equals(Sys.getStation().getStationName() + ":c"))
        {
            BComponent root = (BComponent) BOrd.make("slot:/").get(service, null);
            return HGridBuilder.dictsToGrid(new HDict[] { makeNavResult(root) });
        }
        // ComponentSpace component
        else if (navId.startsWith(Sys.getStation().getStationName() + ":c."))
        {
            NHRef nh = NHRef.make(HRef.make(navId));
            BOrd ord = BOrd.make("station:|" + nh.getPath());
            BComponent comp = (BComponent) ord.get(service, null);

            BComponent kids[] = comp.getChildComponents();
            Array dicts = new Array(HDict.class);
            for (int i = 0; i < kids.length; i++)
                dicts.add(makeNavResult(kids[i]));
            return HGridBuilder.dictsToGrid((HDict[]) dicts.trim());
        }
        else throw new BajaRuntimeException("Cannot lookup nav for " + navId);
    }

    /**
      * Iterator through all the points
      */
    public ConfigStorehouseIterator makeIterator()
    {
        return new ConfigStorehouseIterator(this);
    }

    /**
      * Try to find the point that goes with a history,
      * or return null.
      */
    public BControlPoint lookupPointFromHistory(BHistoryConfig cfg)
    {
        // local history
        if (cfg.getId().getDeviceName().equals(Sys.getStation().getStationName()))
        {
            BOrd[] ords = cfg.getSource().toArray();
            if (ords.length != 1) new IllegalStateException(
                "invalid Source: " + cfg.getSource());

            try
            {
                BComponent source = (BComponent) ords[0].resolve(service, null).get();

                // The source is not always a BHistoryExt.  E.g. for 
                // LogHistory its the LogHistoryService.
                if (source instanceof BHistoryExt)
                {
                    if (source.getParent() instanceof BControlPoint)
                        return (BControlPoint) source.getParent();
                }
            }
            catch (UnresolvedException e)
            {
                return null;
            }

            return null;
        }
        // look for imported point that goes with history (if any)
        else
        {
            RemotePoint remote = RemotePoint.fromHistoryConfig(cfg);
            if (remote == null) return null;

            return server.getCache().getControlPoint(remote);
        }
    }

////////////////////////////////////////////////////////////////
// private
////////////////////////////////////////////////////////////////

    private HDict makeNavResult(BComponent comp)
    {
        HDictBuilder hdb = new HDictBuilder();

        // add a navId, but only if this component is not a leaf
        if (comp.getChildComponents().length > 0)
            hdb.add("navId", NHRef.make(comp).getHRef().val);

        if (isVisibleComponent(comp))
        {
            hdb.add(createComponentTags(comp));
        }
        else
        {
            String dis = comp.getDisplayName(null);
            if (dis != null) hdb.add("dis", dis);
            hdb.add("axType", comp.getType().toString());
            hdb.add("axSlotPath", comp.getSlotPath().toString());
        }

        return hdb.toDict();
    }

    /**
      * Find the imported point that goes with an imported history, 
      * or return null.  
      * <p>
      * This method is rather inefficient.  When operating on histories
      * in bulk, e.g. inside NHServer.iterator(), a different approach 
      * should be used.
      */
    private BControlPoint lookupRemotePoint(
        BHistoryConfig cfg, RemotePoint remote)
    {
        // look up the station
        BDeviceNetwork network = service.getNiagaraNetwork();
        BDevice station = (BDevice) network.get(remote.getStationName());
        if (station == null) return null;

        // look up the points
        // this fetches from sub-folders too
        BPointDeviceExt pointDevExt = (BPointDeviceExt) station.get("points");
        BControlPoint[] points = pointDevExt.getPoints(); 

        // find a point with matching slot path
        for (int i = 0; i < points.length; i++)
        {
            BControlPoint point = points[i];

            // Check for a NiagaraProxyExt
            BAbstractProxyExt proxyExt = point.getProxyExt();
            if (!proxyExt.getType().is(RemotePoint.NIAGARA_PROXY_EXT)) continue;

            // "pointId" seems to always contain the slotPath on 
            // the remote host.
            String slotPath = proxyExt.get("pointId").toString();

            // found it!
            if (slotPath.equals(remote.getSlotPath().toString()))
                return point;
        }

        // no such point
        return null;
    }

    private static int getControlPointKind(BControlPoint point)
    {
        if      (point instanceof BNumericPoint) return NUMERIC_KIND;
        else if (point instanceof BBooleanPoint) return BOOLEAN_KIND;
        else if (point instanceof BEnumPoint)    return ENUM_KIND;
        else if (point instanceof BStringPoint)  return STRING_KIND;

        else return UNKNOWN_KIND;
    }

    private static String makeStatusString(BStatus status)
    {
        if (status.isOk())
            return "ok";

        if (status.isDisabled())     return "disabled";
        if (status.isFault())        return "fault";
        if (status.isDown())         return "down";
        if (status.isAlarm())        return "alarm";
        if (status.isStale())        return "stale";
        if (status.isOverridden())   return "overridden";
        if (status.isNull())         return "null";
        if (status.isUnackedAlarm()) return "unackedAlarm";

        throw new IllegalStateException();
    }
}

