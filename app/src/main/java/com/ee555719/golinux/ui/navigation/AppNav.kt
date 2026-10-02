package com.ee555719.golinux.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ee555719.golinux.R
import com.ee555719.golinux.ui.MainViewModel
import com.ee555719.golinux.ui.screens.BackupScreen
import com.ee555719.golinux.ui.screens.DiskIsoScreen
import com.ee555719.golinux.ui.screens.HomeScreen
import com.ee555719.golinux.ui.screens.PortForwardScreen
import com.ee555719.golinux.ui.screens.SettingsScreen
import com.ee555719.golinux.ui.screens.TerminalScreen

private data class Tab(val route: String, val label: String)

private val tabs = listOf(
    Tab("home", "首页"),
    Tab("ports", "端口"),
    Tab("terminal/ssh", "终端"),
    Tab("disk", "磁盘"),
    Tab("backup", "备份"),
    Tab("settings", "设置")
)

@Composable
fun AppNav(vm: MainViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    val selected = currentRoute == tab.route ||
                            (tab.route == "terminal/ssh" && currentRoute?.startsWith("terminal") == true)
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            when (tab.route) {
                                "home" -> Icon(Icons.Default.Home, contentDescription = tab.label)
                                "ports" -> Icon(Icons.Default.Share, contentDescription = tab.label)
                                "terminal/ssh" -> Icon(
                                    painterResource(R.drawable.ic_notification),
                                    contentDescription = tab.label
                                )
                                "disk" -> Icon(Icons.Default.Refresh, contentDescription = tab.label)
                                "backup" -> Icon(Icons.Default.List, contentDescription = tab.label)
                                else -> Icon(Icons.Default.Settings, contentDescription = tab.label)
                            }
                        },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") { HomeScreen(vm, nav) }
            composable("ports") { PortForwardScreen(vm) }
            composable("terminal/{mode}") { entry ->
                TerminalScreen(vm, entry.arguments?.getString("mode") ?: "ssh")
            }
            composable("disk") { DiskIsoScreen(vm) }
            composable("backup") { BackupScreen(vm) }
            composable("settings") { SettingsScreen(vm) }
        }
    }
}
