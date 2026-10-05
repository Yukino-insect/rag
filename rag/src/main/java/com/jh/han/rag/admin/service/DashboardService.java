
package com.jh.han.rag.admin.service;

import com.jh.han.rag.admin.controller.vo.DashboardOverviewVO;
import com.jh.han.rag.admin.controller.vo.DashboardPerformanceVO;
import com.jh.han.rag.admin.controller.vo.DashboardTrendsVO;

public interface DashboardService {

    DashboardOverviewVO loadOverview(String window);

    DashboardPerformanceVO loadPerformance(String window);

    DashboardTrendsVO loadTrends(String metric, String window, String granularity);
}
