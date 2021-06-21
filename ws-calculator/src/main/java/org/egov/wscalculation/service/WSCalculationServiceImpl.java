package org.egov.wscalculation.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.User;
import org.egov.mdms.model.MdmsCriteriaReq;
import org.egov.tracer.model.CustomException;
import org.egov.wscalculation.config.WSCalculationConfiguration;
import org.egov.wscalculation.constants.WSCalculationConstant;
import org.egov.wscalculation.producer.WSCalculationProducer;
import org.egov.wscalculation.repository.BillGeneratorDao;
import org.egov.wscalculation.repository.ServiceRequestRepository;
import org.egov.wscalculation.repository.WSCalculationDao;
import org.egov.wscalculation.util.CalculatorUtil;
import org.egov.wscalculation.util.WSCalculationUtil;
import org.egov.wscalculation.web.models.AdhocTaxReq;
import org.egov.wscalculation.web.models.BillGenerationSearchCriteria;
import org.egov.wscalculation.web.models.BillGeneratorReq;
import org.egov.wscalculation.web.models.BillScheduler;
import org.egov.wscalculation.web.models.BillScheduler.StatusEnum;
import org.egov.wscalculation.web.models.Calculation;
import org.egov.wscalculation.web.models.CalculationCriteria;
import org.egov.wscalculation.web.models.CalculationReq;
import org.egov.wscalculation.web.models.Property;
import org.egov.wscalculation.web.models.RequestInfoWrapper;
import org.egov.wscalculation.web.models.TaxHeadCategory;
import org.egov.wscalculation.web.models.TaxHeadEstimate;
import org.egov.wscalculation.web.models.TaxHeadMaster;
import org.egov.wscalculation.web.models.WaterConnection;
import org.egov.wscalculation.web.models.WaterConnectionRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.google.common.collect.ImmutableSet;
import com.jayway.jsonpath.JsonPath;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class WSCalculationServiceImpl implements WSCalculationService {

	@Autowired
	private PayService payService;

	@Autowired
	private EstimationService estimationService;
	
	@Autowired
	private CalculatorUtil calculatorUtil;
	
	@Autowired
	private DemandService demandService;
	
	@Autowired
	private MasterDataService masterDataService; 

	@Autowired
	private WSCalculationDao wSCalculationDao;
	
	@Autowired
	private ServiceRequestRepository repository;
	
	@Autowired
	private WSCalculationUtil wSCalculationUtil;
	
	@Autowired
	private BillGeneratorService billGeneratorService;
	
	@Autowired
	private WSCalculationProducer producer;
	
	@Autowired
	private WSCalculationConfiguration configs;
	
	@Autowired
	private BillGeneratorDao billGeneratorDao;

	/**
	 * Get CalculationReq and Calculate the Tax Head on Water Charge And Estimation Charge
	 */
	public List<Calculation> getCalculation(CalculationReq request) {
		List<Calculation> calculations;

		Map<String, Object> masterMap;
		if (request.getIsconnectionCalculation()) {
			//Calculate and create demand for connection
			masterMap = masterDataService.loadMasterData(request.getRequestInfo(),
					request.getCalculationCriteria().get(0).getTenantId());
			calculations = getCalculations(request, masterMap);
		} else {
			//Calculate and create demand for application
			masterMap = masterDataService.loadExemptionMaster(request.getRequestInfo(),
					request.getCalculationCriteria().get(0).getTenantId());
			calculations = getFeeCalculation(request, masterMap);
		}
		demandService.generateDemand(request, calculations, masterMap, request.getIsconnectionCalculation());
		unsetWaterConnection(calculations);
		return calculations;
	}
	
	
	/**
	 * 
	 * 
	 * @param request - Calculation Request Object
	 * @return List of calculation.
	 */
	public List<Calculation> bulkDemandGeneration(CalculationReq request, Map<String, Object> masterMap) {
		List<Calculation> calculations = getCalculations(request, masterMap);
		demandService.generateDemandForBillingCycleInBulk(request, calculations, masterMap, true);
		return calculations;
	}

	/**
	 * 
	 * @param request - Calculation Request Object
	 * @return list of calculation based on request
	 */
	public List<Calculation> getEstimation(CalculationReq request) {
		Map<String, Object> masterData = masterDataService.loadExemptionMaster(request.getRequestInfo(),
				request.getCalculationCriteria().get(0).getTenantId());
		List<Calculation> calculations = getFeeCalculation(request, masterData);
		unsetWaterConnection(calculations);
		return calculations;
	}
	/**
	 * It will take calculation and return calculation with tax head code 
	 * 
	 * @param requestInfo Request Info Object
	 * @param criteria Calculation criteria on meter charge
	 * @param estimatesAndBillingSlabs Billing Slabs
	 * @param masterMap Master MDMS Data
	 * @return Calculation With Tax head
	 */
	public Calculation getCalculation(CalculationReq request, CalculationCriteria criteria,
			Map<String, List> estimatesAndBillingSlabs, Map<String, Object> masterMap, boolean isConnectionFee) {

		@SuppressWarnings("unchecked")
		List<TaxHeadEstimate> estimates = estimatesAndBillingSlabs.get("estimates");
		@SuppressWarnings("unchecked")
		List<String> billingSlabIds = estimatesAndBillingSlabs.get("billingSlabIds");
		WaterConnection waterConnection = criteria.getWaterConnection();
		Property property = wSCalculationUtil.getProperty(
				WaterConnectionRequest.builder().waterConnection(waterConnection).requestInfo(request.getRequestInfo()).build());
		
		String tenantId = null != property.getTenantId() ? property.getTenantId() : criteria.getTenantId();

		@SuppressWarnings("unchecked")
		Map<String, TaxHeadCategory> taxHeadCategoryMap = ((List<TaxHeadMaster>) masterMap
				.get(WSCalculationConstant.TAXHEADMASTER_MASTER_KEY)).stream()
						.collect(Collectors.toMap(TaxHeadMaster::getCode, TaxHeadMaster::getCategory, (OldValue, NewValue) -> NewValue));

		BigDecimal taxAmt = BigDecimal.ZERO;
		BigDecimal waterCharge = BigDecimal.ZERO;
		BigDecimal penalty = BigDecimal.ZERO;
		BigDecimal exemption = BigDecimal.ZERO;
		BigDecimal rebate = BigDecimal.ZERO;
		BigDecimal fee = BigDecimal.ZERO;

		for (TaxHeadEstimate estimate : estimates) {

			TaxHeadCategory category = taxHeadCategoryMap.get(estimate.getTaxHeadCode());
			estimate.setCategory(category);

			switch (category) {

			case CHARGES:
				waterCharge = waterCharge.add(estimate.getEstimateAmount());
				break;

			case PENALTY:
				penalty = penalty.add(estimate.getEstimateAmount());
				break;

			case REBATE:
				rebate = rebate.add(estimate.getEstimateAmount());
				break;

			case EXEMPTION:
				exemption = exemption.add(estimate.getEstimateAmount());
				break;
			case FEE:
				fee = fee.add(estimate.getEstimateAmount());
				break;
			default:
				taxAmt = taxAmt.add(estimate.getEstimateAmount());
				break;
			}
		}
		TaxHeadEstimate decimalEstimate = payService.roundOfDecimals(taxAmt.add(penalty).add(waterCharge).add(fee),
				rebate.add(exemption), isConnectionFee);
		if (null != decimalEstimate) {
			decimalEstimate.setCategory(taxHeadCategoryMap.get(decimalEstimate.getTaxHeadCode()));
			estimates.add(decimalEstimate);
			if (decimalEstimate.getEstimateAmount().compareTo(BigDecimal.ZERO) >= 0)
				taxAmt = taxAmt.add(decimalEstimate.getEstimateAmount());
			else
				rebate = rebate.add(decimalEstimate.getEstimateAmount());
		}

		BigDecimal totalAmount = taxAmt.add(penalty).add(rebate).add(exemption).add(waterCharge).add(fee);
		return Calculation.builder().totalAmount(totalAmount).taxAmount(taxAmt).penalty(penalty).exemption(exemption)
				.charge(waterCharge).fee(fee).waterConnection(waterConnection).rebate(rebate).tenantId(tenantId)
				.taxHeadEstimates(estimates).billingSlabIds(billingSlabIds).connectionNo(criteria.getConnectionNo()).applicationNO(criteria.getApplicationNo())
				.build();
	}
	
	/**
	 * 
	 * @param request would be calculations request
	 * @param masterMap master data
	 * @return all calculations including water charge and taxhead on that
	 */
	List<Calculation> getCalculations(CalculationReq request, Map<String, Object> masterMap) {
		List<Calculation> calculations = new ArrayList<>(request.getCalculationCriteria().size());
		for (CalculationCriteria criteria : request.getCalculationCriteria()) {
			try {
				if(criteria.getFrom() == null || criteria.getTo() ==null || criteria.getFrom() <= 0 || criteria.getTo() <= 0){
					criteria.setFrom(request.getTaxPeriodFrom());
					criteria.setTo(request.getTaxPeriodTo());
				}
				Map<String, List> estimationMap = estimationService.getEstimationMap(criteria, request,
						masterMap);
				ArrayList<?> billingFrequencyMap = (ArrayList<?>) masterMap
						.get(WSCalculationConstant.Billing_Period_Master);
				masterDataService.enrichBillingPeriod(criteria, billingFrequencyMap, masterMap);
				Calculation calculation = getCalculation(request, criteria, estimationMap, masterMap, true);
				calculation.setFrom(criteria.getFrom());
				calculation.setTo(criteria.getTo());
				calculations.add(calculation);
			}catch (Exception e) {
				e.printStackTrace();
			}
		}
		return calculations;
	}


	@Override
	public void jobScheduler() {
		// TODO Auto-generated method stub
		ArrayList<String> tenantIds = wSCalculationDao.searchTenantIds();

		for (String tenantId : tenantIds) {
			RequestInfo requestInfo = new RequestInfo();
			User user = new User();
			user.setTenantId(tenantId);
			requestInfo.setUserInfo(user);
			String jsonPath = WSCalculationConstant.JSONPATH_ROOT_FOR_BilingPeriod;
			MdmsCriteriaReq mdmsCriteriaReq = calculatorUtil.getBillingFrequency(requestInfo, tenantId);
			StringBuilder url = calculatorUtil.getMdmsSearchUrl();
			Object res = repository.fetchResult(url, mdmsCriteriaReq);
			if (res == null) {
				throw new CustomException("MDMS_ERROR_FOR_BILLING_FREQUENCY",
						"ERROR IN FETCHING THE BILLING FREQUENCY");
			}
			ArrayList<?> mdmsResponse = JsonPath.read(res, jsonPath);
			getBillingPeriod(mdmsResponse, requestInfo, tenantId);
		}
	}
	

	@SuppressWarnings("unchecked")
	public void getBillingPeriod(ArrayList<?> mdmsResponse, RequestInfo requestInfo, String tenantId) {
		log.info("Billing Frequency Map" + mdmsResponse.toString());
		Map<String, Object> master = (Map<String, Object>) mdmsResponse.get(0);
		LocalDateTime demandStartingDate = LocalDateTime.now();
		Long demandGenerateDateMillis = (Long) master.get(WSCalculationConstant.Demand_Generate_Date_String);

		String connectionType = "Non-metred";

		if (demandStartingDate.getDayOfMonth() == (demandGenerateDateMillis) / 86400) {

			ArrayList<String> connectionNos = wSCalculationDao.searchConnectionNos(connectionType, tenantId);
			for (String connectionNo : connectionNos) {

				CalculationReq calculationReq = new CalculationReq();
				CalculationCriteria calculationCriteria = new CalculationCriteria();
				calculationCriteria.setTenantId(tenantId);
				calculationCriteria.setConnectionNo(connectionNo);

				List<CalculationCriteria> calculationCriteriaList = new ArrayList<>();
				calculationCriteriaList.add(calculationCriteria);

				calculationReq.setRequestInfo(requestInfo);
				calculationReq.setCalculationCriteria(calculationCriteriaList);
				calculationReq.setIsconnectionCalculation(true);
				getCalculation(calculationReq);

			}
		}
	}

	/**
	 * Generate Demand Based on Time (Monthly, Quarterly, Yearly)
	 */
	public void generateDemandBasedOnTimePeriod(RequestInfo requestInfo) {
		DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
		LocalDateTime date = LocalDateTime.now();
		log.info("Time schedule start for water demand generation on : " + date.format(dateTimeFormatter));
//		List<String> tenantIds = wSCalculationDao.getTenantId();
		List<String> tenantIds = new ArrayList<>();
		tenantIds.add("pb.fazilka");
		if (tenantIds.isEmpty()) {
			log.info("No tenants are found for generating demand");
			return;
		}
		log.info("Tenant Ids : " + tenantIds.toString());
		tenantIds.forEach(tenantId -> {
			try {
				
				demandService.generateDemandForTenantId(tenantId, requestInfo);
				
			} catch (Exception e) {
				log.error("Exception occured while generating demand for tenant: " + tenantId);
				e.printStackTrace();
			}
		});
	}
	
	/**
	 * Generate bill Based on Time (Monthly, Quarterly, Yearly)
	 */
	public void generateBillBasedLocality(RequestInfo requestInfo) {
		DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
		LocalDateTime date = LocalDateTime.now();
		log.info("Time schedule start for water bill generation on : " + date.format(dateTimeFormatter));

		BillGenerationSearchCriteria criteria = new BillGenerationSearchCriteria();
		criteria.setStatus(WSCalculationConstant.INITIATED_CONST);

		List<BillScheduler> billSchedularList = billGeneratorService.getBillGenerationDetails(criteria);
		if (billSchedularList.isEmpty())
			return;
		log.info("billSchedularList count : " + billSchedularList.size());
		for (BillScheduler billSchedular : billSchedularList) {
			try {

				billGeneratorDao.updateBillSchedularStatus(billSchedular.getId(), StatusEnum.INPROGRESS);
				log.info("Updated Bill Schedular Status To INPROGRESS");

				requestInfo.getUserInfo().setTenantId(billSchedular.getTenantId() != null ? billSchedular.getTenantId() : requestInfo.getUserInfo().getTenantId());
				RequestInfoWrapper requestInfoWrapper = RequestInfoWrapper.builder().requestInfo(requestInfo).build();

				List<String> connectionNos = wSCalculationDao.getConnectionsNoByLocality( billSchedular.getTenantId(), WSCalculationConstant.nonMeterdConnection, billSchedular.getLocality());
				//			connectionNos.add("0603000002");
				//			connectionNos.add("0603009718");
				//			connectionNos.add("0603000001");
				if (connectionNos == null || connectionNos.isEmpty()) {
					billGeneratorDao.updateBillSchedularStatus(billSchedular.getId(), StatusEnum.COMPLETED);
					continue;
				}

				Collection<List<String>> partitionConectionNoList = partitionBasedOnSize(connectionNos, configs.getBulkBillGenerateCount());
				log.info("partitionConectionNoList size: {}, Producer ConsumerCodes size : {} and BulkBillGenerateCount: {}",partitionConectionNoList.size(), connectionNos.size(), configs.getBulkBillGenerateCount());
				int threadSleepCount = 1;
				int count = 1;
				for (List<String>  conectionNoList : partitionConectionNoList) {

					BillGeneratorReq billGeneraterReq = BillGeneratorReq
							.builder()
							.requestInfoWrapper(requestInfoWrapper)
							.tenantId(billSchedular.getTenantId())
							.consumerCodes(ImmutableSet.copyOf(conectionNoList))
							.billSchedular(billSchedular)
							.build();

					producer.push(configs.getBillGenerateSchedulerTopic(), billGeneraterReq);
					log.info("Bill Scheduler pushed connections size:{} to kafka topic of batch no: ", conectionNoList.size(), count++);

					if(threadSleepCount == 2) {
						//Pausing the controller for 10 seconds after every two batches pushed to Kafka topic
						Thread.sleep(10000);
						threadSleepCount=1;
					}
					threadSleepCount++;

				}
				billGeneratorDao.updateBillSchedularStatus(billSchedular.getId(), StatusEnum.COMPLETED);
				log.info("Updated Bill Schedular Status To COMPLETED");

			}catch (Exception e) {
				log.error("Execption occured while generating bills for tenant"+billSchedular.getTenantId()+" and locality: "+billSchedular.getLocality());
			}

		}
	}
	
	/**
	 * 
	 * @param request - Calculation Request Object
	 * @param masterMap - Master MDMS Data
	 * @return list of calculation based on estimation criteria
	 */
	List<Calculation> getFeeCalculation(CalculationReq request, Map<String, Object> masterMap) {
		List<Calculation> calculations = new ArrayList<>(request.getCalculationCriteria().size());
		for (CalculationCriteria criteria : request.getCalculationCriteria()) {
			Map<String, List> estimationMap = estimationService.getFeeEstimation(criteria, request.getRequestInfo(),
					masterMap);
			masterDataService.enrichBillingPeriodForFee(masterMap);
			Calculation calculation = getCalculation(request, criteria, estimationMap, masterMap, false);
			calculations.add(calculation);
		}
		return calculations;
	}
	
	public void unsetWaterConnection(List<Calculation> calculation) {
		calculation.forEach(cal -> cal.setWaterConnection(null));
	}
	
	/**
	 * Add adhoc tax to demand
	 * @param adhocTaxReq - Adhox Tax Request Object
	 * @return List of Calculation
	 */
	public List<Calculation> applyAdhocTax(AdhocTaxReq adhocTaxReq) {
		List<TaxHeadEstimate> estimates = new ArrayList<>();
		if (!(adhocTaxReq.getAdhocpenalty().compareTo(BigDecimal.ZERO) == 0))
			estimates.add(TaxHeadEstimate.builder().taxHeadCode(WSCalculationConstant.WS_TIME_ADHOC_PENALTY)
					.estimateAmount(adhocTaxReq.getAdhocpenalty().setScale(2, 2)).build());
		if (!(adhocTaxReq.getAdhocrebate().compareTo(BigDecimal.ZERO) == 0))
			estimates.add(TaxHeadEstimate.builder().taxHeadCode(WSCalculationConstant.WS_TIME_ADHOC_REBATE)
					.estimateAmount(adhocTaxReq.getAdhocrebate().setScale(2, 2).negate()).build());
		Calculation calculation = Calculation.builder()
				.tenantId(adhocTaxReq.getRequestInfo().getUserInfo().getTenantId())
				.connectionNo(adhocTaxReq.getConsumerCode()).taxHeadEstimates(estimates).build();
		List<Calculation> calculations = Collections.singletonList(calculation);
		return demandService.updateDemandForAdhocTax(adhocTaxReq.getRequestInfo(), calculations);
	}
	
	static <T> Collection<List<T>> partitionBasedOnSize(List<T> inputList, int size) {
        final AtomicInteger counter = new AtomicInteger(0);
        return inputList.stream()
                    .collect(Collectors.groupingBy(s -> counter.getAndIncrement()/size))
                    .values();
	}
}
