package com.example.pulse.screen

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.pulse.viewmodel.PerformanceViewModel
import com.example.pulse.viewmodel.ScheduleViewModel
import com.example.pulse.viewmodel.Teacher

@Composable
fun TeachersScreen(
    navController: NavController,
    viewModel: ScheduleViewModel = viewModel(),
    perfViewModel: PerformanceViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val perfSemesters = perfViewModel.semesters

    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("Все") }

    val myTeachersList = remember(state.lessons, perfSemesters) {
        val fromPerformance = perfSemesters.flatMap { it.grades }.map { it.teacher }
        val fromSchedule = state.lessons.map { it.teacher }
        (fromPerformance + fromSchedule).filter { it.isNotBlank() && it != "—" }.toSet()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    imageVector = Icons.Default.ArrowBackIosNew,
                    contentDescription = "Назад",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Учителя", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .background(SurfaceDark, RoundedCornerShape(16.dp))
                    .border(1.dp, PrimaryPurple.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Search, contentDescription = "Поиск", tint = TextGray, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                    cursorBrush = SolidColor(PrimaryPurple),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        if (searchQuery.isEmpty()) {
                            Text("Поиск учителя...", color = TextGray.copy(alpha = 0.7f), fontSize = 16.sp)
                        }
                        innerTextField()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceDark, RoundedCornerShape(16.dp))
                .border(1.dp, PrimaryPurple.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                .padding(4.dp)
        ) {
            TeacherTabButton(text = "Все", isSelected = selectedTab == "Все", modifier = Modifier.weight(1f)) {
                if (selectedTab != "Все") selectedTab = "Все"
            }
            TeacherTabButton(text = "Мои", isSelected = selectedTab == "Мои", modifier = Modifier.weight(1f)) {
                if (selectedTab != "Мои") selectedTab = "Мои"
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        val filteredTeachers = remember(state.teachers, searchQuery, selectedTab, myTeachersList) {
            state.teachers.filter { teacher ->
                val matchesSearch = if (searchQuery.isBlank()) true else (teacher.name.contains(searchQuery, ignoreCase = true) || teacher.subject.contains(searchQuery, ignoreCase = true))
                val matchesTab = if (selectedTab == "Мои") {
                    myTeachersList.any { myTeacher ->
                        teacher.name.contains(myTeacher, ignoreCase = true) || myTeacher.contains(teacher.name, ignoreCase = true)
                    }
                } else true

                matchesSearch && matchesTab
            }
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (filteredTeachers.isEmpty()) {
                item {
                    val emptyText = if (selectedTab == "Мои") "У вас пока нет сохраненных учителей" else "Учителя не найдены"
                    Text(emptyText, color = TextGray, modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center)
                }
            } else {
                items(filteredTeachers, key = { it.name }) { teacher ->
                    TeacherCard(teacher = teacher) {
                        val encodedName = Uri.encode(teacher.name)
                        navController.navigate("teacher_schedule/$encodedName")
                    }
                }
            }
            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun TeacherTabButton(text: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) PrimaryPurple else Color.Transparent),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(0.dp),
        elevation = null
    ) {
        Text(
            text = text,
            color = if (isSelected) Color.White else TextGray,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
fun TeacherCard(teacher: Teacher, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = BorderStroke(1.dp, PrimaryPurple.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(teacher.avatarColor))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = teacher.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 20.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = teacher.subject, color = TextGray, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.MeetingRoom, contentDescription = null, tint = TextGray, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(teacher.room, color = TextGray, fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Email, contentDescription = null, tint = TextGray, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(teacher.email, color = TextGray, fontSize = 12.sp)
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = "Перейти", tint = PrimaryPurple)
        }
    }
}