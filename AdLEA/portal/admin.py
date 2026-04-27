from django.contrib import admin
from .models import BlacklistedAd


@admin.register(BlacklistedAd)
class BlacklistedAdAdmin(admin.ModelAdmin):
    list_display = ('title', 'user', 'created_at', 'reviewed')
    list_filter = ("reviewed", "created_at")
    search_fields = ('title', 'body', 'link_url')
    readonly_fields = ('created_at',)


