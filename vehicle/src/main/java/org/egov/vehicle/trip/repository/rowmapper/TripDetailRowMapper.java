package org.egov.vehicle.trip.repository.rowmapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.egov.vehicle.trip.web.model.VehicleTripDetail;
import org.egov.vehicle.web.model.AuditDetails;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
@Component
public class TripDetailRowMapper implements ResultSetExtractor<List<VehicleTripDetail>> {

	@Autowired
	private ObjectMapper mapper;

	@SuppressWarnings("rawtypes")
	@Override
	public List<VehicleTripDetail> extractData(ResultSet rs) throws SQLException, DataAccessException {
		
		Map<String, VehicleTripDetail> tripDetailMap = new LinkedHashMap<String, VehicleTripDetail>();

		while (rs.next()) {
			String id = rs.getString("id");
			String tenantId = rs.getString("tenantid");
			String trip_id = rs.getString("trip_id");
			String referenceno = rs.getString("referenceno");
			String referencestatus = rs.getString("referencestatus");
			String additionaldetails = rs.getString("additionaldetails");
			String status = rs.getString("status");
			Long itemstarttime = rs.getLong("itemstarttime");
			Long itemendtime = rs.getLong("itemendtime");
			Double volume = rs.getDouble("volume");
			
			String createdBy = rs.getString("createdby");
			String lastModifiedBy = rs.getString("lastmodifiedby");
			Long createdTime = rs.getLong("createdtime");
			Long lastModifiedTime = rs.getLong("lastmodifiedtime");
			
			AuditDetails audit = AuditDetails.builder().createdBy(createdBy).lastModifiedBy(lastModifiedBy).createdTime(createdTime)
					.lastModifiedTime(lastModifiedTime).build();
			tripDetailMap.put(id, VehicleTripDetail.builder().id(id).tenantId(tenantId).referenceNo(referenceno).referenceStatus(referencestatus)
					.additionalDetails(additionaldetails).status(VehicleTripDetail.StatusEnum.fromValue(status)).itemStartTime(itemstarttime)
					.itemEndTime(itemendtime).volume(volume).auditDetails(audit).build());
		
			
		}
		return new ArrayList<>(tripDetailMap.values());
	}
}