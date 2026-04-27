from django.urls import path
from . import views

urlpatterns = [

    path('', views.landing_page, name='landing'),

    path('register/', views.register, name='register'),

    path('login/', views.login_user, name='login'),

    path('dashboard/', views.dashboard, name='dashboard'),

    path('search/', views.search_ads, name='search_ads'),

    path("report-ad/", views.report_ad, name="report_ad"),

    path('logout/', views.logout_user, name='logout'),
]